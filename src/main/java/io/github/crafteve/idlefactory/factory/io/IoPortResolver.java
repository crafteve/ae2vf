package io.github.crafteve.idlefactory.factory.io;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.capability.LevelCapabilityCache;
import io.github.crafteve.idlefactory.factory.machine.ResourceSpec;
import io.github.crafteve.idlefactory.factory.storage.AeKeySupport;

import appeng.api.AECapabilities;
import appeng.api.behaviors.ExternalStorageStrategy;
import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.storage.MEStorage;
import appeng.helpers.patternprovider.PatternProviderTarget;
import appeng.me.storage.CompositeStorage;
import appeng.parts.automation.StackWorldBehaviors;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 机器侧唯一的写入口：把「一台机器 + 一套规则」翻译成可搬运的通道，并负责选面与模拟。
 *
 * <p>被动搬运与主动派单共用本类，区别只在两头：被动要的是 {@link ResourceAdapter}（走 NeoForge
 * 能力），主动要的是 {@link Faces} 快照（走 AE2 的派单目标与外部存储）。两套后端的语义不同，
 * 不能合成一条通道，但「按面选 + 模拟 + 写入」的骨架只有这一处。</p>
 *
 * <p>面记忆只有两份来源：规则里配置的面（最高优先）与经验面（单元的结论里记住上次搬通的面，
 * 仅在没配面时兜底）。写入被拒（REJECTED）就由分析器就地清掉经验面，下一轮重新探。</p>
 */
public final class IoPortResolver {
    /** 能量面探测用的模拟量：Mekanism 的 FE↔J 换算会在小数值上截断。 */
    private static final int ENERGY_PROBE = 1000;
    /** 物品面探测的模拟量：1 个就足以判断这个面收不收 / 抽不抽得出来。 */
    private static final int ITEM_PROBE = 1;
    /** 固定顺序的六面，避免 {@code Direction.values()} 每次复制数组。 */
    private static final Direction[] FACES = Direction.values();
    /** 外部存储包装用的空回调。 */
    private static final Runnable NOOP = () -> {
    };

    /**
     * 被动侧的一条搬运通道。
     *
     * @param adapter 适配器
     * @param face    实际用的面；{@code null} 表示无向
     */
    public record Resolved(ResourceAdapter adapter, @Nullable Direction face) {
    }

    private IoPortResolver() {
        // 纯静态工具类
    }

    /**
     * 解析一条规则：配置面优先，其次经验面兜底，最后扫面。
     *
     * @param level 机器所在维度
     * @param pos   机器坐标
     * @param spec  规则
     * @param side  方向：输入还是输出
     * @param caps  能力句柄缓存；{@code null} 时直接查世界
     * @param hint  经验面；{@code null} 表示没有
     * @return 通道；没有可用 handler 时为 {@code null}
     */
    @Nullable
    public static Resolved resolve(ServerLevel level, BlockPos pos, ResourceSpec spec, IoSide side,
            @Nullable LevelCapabilityCache caps, @Nullable Direction hint) {
        Direction configured = spec.face();
        if (configured != null) {
            return build(level, pos, spec, caps, configured);
        }
        if (hint != null) {
            Resolved direct = build(level, pos, spec, caps, hint);
            if (direct != null) {
                return direct;
            }
        }
        return switch (AeKeySupport.kind(spec.key())) {
            case ITEM -> sweepItem(level, pos, spec, side, caps);
            case FLUID -> sweepFluid(level, pos, spec, side, caps);
            case ENERGY -> sweepEnergy(level, pos, spec, side, caps);
        };
    }

    /**
     * 按指定面构建适配器（不扫面、不模拟）。
     */
    @Nullable
    private static Resolved build(ServerLevel level, BlockPos pos, ResourceSpec spec,
            @Nullable LevelCapabilityCache caps, @Nullable Direction face) {
        return switch (AeKeySupport.kind(spec.key())) {
            case ITEM -> {
                ItemStack sample = AeKeySupport.toStack(spec.key());
                IItemHandler handler = item(level, pos, caps, face);
                yield handler == null || sample.isEmpty()
                        ? null
                        : new Resolved(new ItemAdapter(handler, sample), face);
            }
            case FLUID -> {
                Fluid fluid = BuiltInRegistries.FLUID.get(spec.key().getId());
                IFluidHandler handler = fluid(level, pos, caps, face);
                yield handler == null || fluid == Fluids.EMPTY
                        ? null
                        : new Resolved(new FluidAdapter(handler, fluid), face);
            }
            case ENERGY -> {
                IEnergyStorage handler = energy(level, pos, caps, face);
                yield handler == null ? null : new Resolved(new EnergyAdapter(handler), face);
            }
        };
    }

    /**
     * 物品：先无向、再逐面。
     *
     * <p>物品规则是无向配置，但「无向查询」不等于「可写」：Mekanism 的 {@code side == null}
     * 返回只读代理，insert/extract 都返回空。因此无向 handler 不存在或当前方向用不了时，必须
     * 逐个面找真正可用的 handler。找不到可用的面时仍回退无向 / 第一个面 handler，让下游报
     * 「满了 / 没料」而不是「没有 handler」。</p>
     */
    @Nullable
    private static Resolved sweepItem(ServerLevel level, BlockPos pos, ResourceSpec spec, IoSide ioSide,
            @Nullable LevelCapabilityCache caps) {
        ItemStack sample = AeKeySupport.toStack(spec.key());
        if (sample.isEmpty()) {
            return null;
        }
        IItemHandler unsided = item(level, pos, caps, null);
        if (unsided != null && itemAccessible(unsided, sample, spec.fuzzy(), ioSide)) {
            return new Resolved(new ItemAdapter(unsided, sample), null);
        }
        IItemHandler firstSide = null;
        Direction firstFace = null;
        for (Direction side : FACES) {
            IItemHandler handler = item(level, pos, caps, side);
            if (handler == null) {
                continue;
            }
            if (firstSide == null) {
                firstSide = handler;
                firstFace = side;
            }
            if (itemAccessible(handler, sample, spec.fuzzy(), ioSide)) {
                return new Resolved(new ItemAdapter(handler, sample), side);
            }
        }
        if (unsided != null) {
            return new Resolved(new ItemAdapter(unsided, sample), null);
        }
        return firstSide == null
                ? null
                : new Resolved(new ItemAdapter(firstSide, sample), firstFace);
    }

    /**
     * 这个 handler 此刻能不能按规则方向服务该物品。
     *
     * <p>精确规则按完整组件比较；模糊规则按注册名，先试默认组件栈、再试规则自带的样本，
     * 最后扫机器里已有的变体（上限 {@link ItemAdapter#MAX_SCAN_SLOTS}），避免只探默认栈
     * 就否掉一个只认带组件变体的面。</p>
     */
    private static boolean itemAccessible(IItemHandler handler, ItemStack sample, boolean fuzzy,
            IoSide ioSide) {
        if (!fuzzy) {
            return probeItem(handler, sample, ioSide);
        }
        ItemStack plain = new ItemStack(sample.getItem());
        if (probeItem(handler, plain, ioSide)) {
            return true;
        }
        if (!ItemStack.isSameItemSameComponents(plain, sample)
                && probeItem(handler, sample, ioSide)) {
            return true;
        }
        int slots = Math.min(handler.getSlots(), ItemAdapter.MAX_SCAN_SLOTS);
        for (int slot = 0; slot < slots; slot++) {
            ItemStack inSlot = handler.getStackInSlot(slot);
            if (!inSlot.isEmpty() && inSlot.getItem() == sample.getItem()
                    && probeItem(handler, inSlot.copyWithCount(1), ioSide)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 用 1 个物品模拟一次「收 / 抽」。
     */
    private static boolean probeItem(IItemHandler handler, ItemStack sample, IoSide ioSide) {
        ItemAdapter adapter = new ItemAdapter(handler, sample);
        return ioSide == IoSide.INPUT
                ? adapter.insert(ITEM_PROBE, true) > 0L
                : adapter.extract(ITEM_PROBE, true) > 0L;
    }

    @Nullable
    private static Resolved sweepFluid(ServerLevel level, BlockPos pos, ResourceSpec spec, IoSide ioSide,
            @Nullable LevelCapabilityCache caps) {
        Fluid fluid = BuiltInRegistries.FLUID.get(spec.key().getId());
        if (fluid == Fluids.EMPTY) {
            return null;
        }
        FluidStack probe = new FluidStack(fluid, 1);
        for (Direction side : FACES) {
            IFluidHandler handler = fluid(level, pos, caps, side);
            if (handler == null) {
                continue;
            }
            int amount = ioSide == IoSide.INPUT
                    ? handler.fill(probe, IFluidHandler.FluidAction.SIMULATE)
                    : handler.drain(probe, IFluidHandler.FluidAction.SIMULATE).getAmount();
            if (amount > 0) {
                return new Resolved(new FluidAdapter(handler, fluid), side);
            }
        }
        IFluidHandler fallback = fluid(level, pos, caps, null);
        return fallback == null ? null : new Resolved(new FluidAdapter(fallback, fluid), null);
    }

    @Nullable
    private static Resolved sweepEnergy(ServerLevel level, BlockPos pos, ResourceSpec spec, IoSide ioSide,
            @Nullable LevelCapabilityCache caps) {
        for (Direction side : FACES) {
            IEnergyStorage handler = energy(level, pos, caps, side);
            if (handler == null) {
                continue;
            }
            // 输入看能不能收，输出看能不能抽——旧代码两边都用 receiveEnergy，是错的
            int probe = ioSide == IoSide.INPUT
                    ? handler.receiveEnergy(ENERGY_PROBE, true)
                    : handler.extractEnergy(ENERGY_PROBE, true);
            if (probe > 0) {
                return new Resolved(new EnergyAdapter(handler), side);
            }
        }
        IEnergyStorage fallback = energy(level, pos, caps, null);
        return fallback == null ? null : new Resolved(new EnergyAdapter(fallback), null);
    }

    @Nullable
    private static IItemHandler item(ServerLevel level, BlockPos pos,
            @Nullable LevelCapabilityCache caps, @Nullable Direction face) {
        return caps == null
                ? level.getCapability(Capabilities.ItemHandler.BLOCK, pos, face)
                : caps.item(pos, face);
    }

    @Nullable
    private static IFluidHandler fluid(ServerLevel level, BlockPos pos,
            @Nullable LevelCapabilityCache caps, @Nullable Direction face) {
        return caps == null
                ? level.getCapability(Capabilities.FluidHandler.BLOCK, pos, face)
                : caps.fluid(pos, face);
    }

    @Nullable
    private static IEnergyStorage energy(ServerLevel level, BlockPos pos,
            @Nullable LevelCapabilityCache caps, @Nullable Direction face) {
        return caps == null
                ? level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, face)
                : caps.energy(pos, face);
    }

    /**
     * 开一次派单 / 回收用的面快照。
     *
     * <p>机器只有一个方块，快照因此只有一个槽位；配置面从 {@code inputs} 里按资源查，
     * 经验面归执行层的结论，不进这里。</p>
     *
     * @param level  方块所在世界
     * @param pos    方块坐标
     * @param be     方块实体；没有时为 {@code null}
     * @param inputs 节点的输入规则（用来查配置面）
     * @param source 动作源
     * @return 快照
     */
    public static Faces faces(ServerLevel level, BlockPos pos, @Nullable BlockEntity be,
            List<ResourceSpec> inputs, IActionSource source) {
        return new Faces(new Faces.Slot(level, pos, be), inputs, source);
    }

    /**
     * 一次派单 / 一次回收里复用的面快照：一个方块、六个面，目标与可抽存储都按面懒解析。
     */
    public static final class Faces {
        /**
         * 已加载的方块。
         *
         * @param level 所在世界
         * @param pos   坐标
         * @param be    方块实体；没有时为 {@code null}
         */
        public record Slot(ServerLevel level, BlockPos pos, @Nullable BlockEntity be) {
        }

        /**
         * 一次选面结果：方块 + 面 + 派单目标。
         *
         * @param slot   方块
         * @param face   面
         * @param target 派单目标
         */
        public record Target(Slot slot, Direction face, PatternProviderTarget target) {
        }

        private final Slot slot;
        private final List<ResourceSpec> inputs;
        private final IActionSource source;
        private final PatternProviderTarget[] targets = new PatternProviderTarget[FACES.length];
        private final boolean[] resolved = new boolean[FACES.length];

        private Faces(Slot slot, List<ResourceSpec> inputs, IActionSource source) {
            this.slot = slot;
            this.inputs = List.copyOf(inputs);
            this.source = source;
        }

        /** 快照覆盖的方块（恒为 1 项，保留列表是为了回收侧按槽位遍历的老接口）。 */
        public List<Slot> slots() {
            return List.of(this.slot);
        }

        /**
         * 某个面的派单目标。
         *
         * @param face 面
         * @return 目标；这个面没有可写的存储时为 {@code null}
         */
        @Nullable
        public PatternProviderTarget target(Direction face) {
            int index = face.get3DDataValue();
            if (!this.resolved[index]) {
                this.resolved[index] = true;
                this.targets[index] = PatternProviderTarget.get(this.slot.level(), this.slot.pos(),
                        this.slot.be(), face, this.source);
            }
            return this.targets[index];
        }

        /**
         * 某个面的可抽取存储。
         *
         * @param face 面
         * @return 存储；没有时为 {@code null}
         */
        @Nullable
        public MEStorage storage(Direction face) {
            BlockEntity be = this.slot.be();
            MEStorage nativeStorage = be != null
                    ? this.slot.level().getCapability(AECapabilities.ME_STORAGE, this.slot.pos(),
                            be.getBlockState(), be, face)
                    : this.slot.level().getCapability(AECapabilities.ME_STORAGE, this.slot.pos(), face);
            if (nativeStorage != null) {
                return nativeStorage;
            }
            Map<AEKeyType, ExternalStorageStrategy> strategies =
                    StackWorldBehaviors.createExternalStorageStrategies(this.slot.level(),
                            this.slot.pos(), face);
            IdentityHashMap<AEKeyType, MEStorage> wrappers = new IdentityHashMap<>(2);
            for (Map.Entry<AEKeyType, ExternalStorageStrategy> entry : strategies.entrySet()) {
                MEStorage wrapper = entry.getValue().createWrapper(false, NOOP);
                if (wrapper != null) {
                    wrappers.put(entry.getKey(), wrapper);
                }
            }
            return wrappers.isEmpty() ? null : new CompositeStorage(wrappers);
        }

        /**
         * 阻挡判定：任何一个面上还有这台机器的样板输入就整单拒绝。
         *
         * @param blockedKeys 这台机器所有样板输入的 key 并集（已去组件）
         * @return 是否撞到阻挡
         */
        public boolean blockingHit(Set<AEKey> blockedKeys) {
            if (blockedKeys.isEmpty()) {
                return false;
            }
            for (Direction face : FACES) {
                PatternProviderTarget target = this.target(face);
                if (target != null && target.containsPatternInput(blockedKeys)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 给一个 key 选面：规则面优先，其次六面模拟，要求能完整收下这一份数量。
         *
         * @param key    资源
         * @param amount 这一份的数量
         * @return 能收的目标；一个都收不了时为 {@code null}
         */
        @Nullable
        public Target select(AEKey key, long amount) {
            Direction preferred = this.ruleFace(key);
            if (preferred != null && this.accepts(preferred, key, amount)) {
                return new Target(this.slot, preferred, this.target(preferred));
            }
            for (Direction face : FACES) {
                if (face == preferred) {
                    continue;
                }
                if (this.accepts(face, key, amount)) {
                    return new Target(this.slot, face, this.target(face));
                }
            }
            return null;
        }

        /**
         * 往选定的目标插入。
         *
         * @param target 选面结果
         * @param key    资源
         * @param amount 数量
         * @param mode   模拟 / 提交
         * @return 实际插入数量
         */
        public long insert(Target target, AEKey key, long amount, Actionable mode) {
            return target.target().insert(key, amount, mode);
        }

        /** 节点输入规则里给这个 key 配的面；先精确、再同注册名的模糊。 */
        @Nullable
        private Direction ruleFace(AEKey key) {
            Direction fuzzy = null;
            for (ResourceSpec spec : this.inputs) {
                if (spec.face() == null) {
                    continue;
                }
                if (spec.key().equals(key)) {
                    return spec.face();
                }
                if (fuzzy == null && spec.fuzzy()
                        && spec.key().getType().equals(key.getType())
                        && spec.key().getId().equals(key.getId())) {
                    fuzzy = spec.face();
                }
            }
            return fuzzy;
        }

        /** 这个面能否完整收下这一份数量：整数份语义下，收不满就不选它。 */
        private boolean accepts(Direction face, AEKey key, long amount) {
            PatternProviderTarget target = this.target(face);
            return target != null && target.insert(key, amount, Actionable.SIMULATE) >= amount;
        }
    }
}
