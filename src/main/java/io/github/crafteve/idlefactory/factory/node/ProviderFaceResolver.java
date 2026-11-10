package io.github.crafteve.idlefactory.factory.node;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.location.BlockKey;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.machine.ResourceSpec;

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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 机器面的解析器：把一台机器所在方块按六个面翻译成派单目标与可抽取存储。
 *
 * <p>虚拟供应器没有自己的方块，材料直接塞进机器本身；机器已经统一为单个方块，所以这里只有
 * 一个槽位。面选择顺序：机器输入规则里配过这个 key 的面优先，其次六面模拟。</p>
 *
 * <p>一次派单 / 一次回收共用一个 {@link Faces} 快照，快照里每个方块的面只解析一次。</p>
 */
final class ProviderFaceResolver {
    /** 固定顺序的六面，避免 {@code Direction.values()} 每次复制数组。 */
    private static final Direction[] FACES = Direction.values();
    /** 外部存储包装用的空回调。 */
    private static final Runnable NOOP = () -> {
    };

    private final NodeRuntime node;

    /**
     * @param node 所属节点
     */
    ProviderFaceResolver(NodeRuntime node) {
        this.node = node;
    }

    /**
     * 开一次派单 / 回收用的面快照。
     *
     * @param machine 目标机器
     * @return 快照；没有任何方块加载时为 {@code null}
     */
    @Nullable
    Faces snapshot(Machine machine) {
        BlockKey block = machine.block();
        ServerLevel level = this.node.levelOf(block);
        if (level == null || !level.isLoaded(block.pos())) {
            // 机器 ONLINE 要求方块可用；未加载就交给健康门禁拦整台机器。
            return null;
        }
        List<Faces.Slot> slots = List.of(
                new Faces.Slot(level, block.pos(), level.getBlockEntity(block.pos())));
        return new Faces(this, slots);
    }

    /**
     * 某个方块某个面的可抽取存储：先看原生 ME 存储能力，没有再用 AE2 的外部存储策略包一层。
     *
     * @param level 方块所在世界
     * @param pos   方块坐标
     * @param face  面
     * @return 存储；这个面没有可抽取的东西时为 {@code null}
     */
    @Nullable
    MEStorage storage(ServerLevel level, BlockPos pos, Direction face) {
        BlockEntity be = level.getBlockEntity(pos);
        MEStorage nativeStorage = be != null
                ? level.getCapability(AECapabilities.ME_STORAGE, pos, be.getBlockState(), be, face)
                : level.getCapability(AECapabilities.ME_STORAGE, pos, face);
        if (nativeStorage != null) {
            return nativeStorage;
        }
        Map<AEKeyType, ExternalStorageStrategy> strategies =
                StackWorldBehaviors.createExternalStorageStrategies(level, pos, face);
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
     * 节点输入规则里给这个 key 配的面。
     *
     * <p>先找完整 key 相同的精确规则，再找同注册名的模糊规则；没配面的规则不参与。</p>
     *
     * @param key 资源
     * @return 面；没有配置时为 {@code null}
     */
    @Nullable
    private Direction ruleFace(AEKey key) {
        List<ResourceSpec> inputs = this.node.node().io().inputs();
        Direction fuzzy = null;
        for (ResourceSpec spec : inputs) {
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

    /** 给 {@link Faces} 读动作源。 */
    NodeRuntime node() {
        return this.node;
    }

    /**
     * 一次派单 / 一次回收里复用的面快照，覆盖整台机器的所有方块。
     *
     * <p>机器只有一个方块，槽位列表因此恒为 1 项；保留列表是因为 {@code Faces} 按槽位索引。
     * 并记住「解析过没有」，某个面确实没有目标时不重复查能力。</p>
     */
    static final class Faces {
        /**
         * 一个已加载的方块。
         *
         * @param level 所在世界
         * @param pos   坐标
         * @param be    方块实体；没有时为 {@code null}
         */
        record Slot(ServerLevel level, BlockPos pos, @Nullable BlockEntity be) {
        }

        /**
         * 一次选面结果：方块 + 面 + 派单目标。
         *
         * @param slot   方块
         * @param face   面
         * @param target 派单目标
         */
        record Target(Slot slot, Direction face, PatternProviderTarget target) {
        }

        private final ProviderFaceResolver owner;
        private final List<Slot> slots;
        private final PatternProviderTarget[] targets;
        private final boolean[] resolved;

        private Faces(ProviderFaceResolver owner, List<Slot> slots) {
            this.owner = owner;
            this.slots = List.copyOf(slots);
            this.targets = new PatternProviderTarget[this.slots.size() * FACES.length];
            this.resolved = new boolean[this.targets.length];
        }

        /** 已加载的方块，按机器方块顺序。 */
        List<Slot> slots() {
            return this.slots;
        }

        /**
         * 某个方块某个面的派单目标。
         *
         * @param slotIndex 方块下标
         * @param face      面
         * @return 目标；这个面没有可写的存储时为 {@code null}
         */
        @Nullable
        PatternProviderTarget target(int slotIndex, Direction face) {
            int index = slotIndex * FACES.length + face.get3DDataValue();
            if (!this.resolved[index]) {
                this.resolved[index] = true;
                Slot slot = this.slots.get(slotIndex);
                IActionSource source = this.owner.node().actionSource();
                this.targets[index] = PatternProviderTarget.get(slot.level(), slot.pos(),
                        slot.be(), face, source);
            }
            return this.targets[index];
        }

        /**
         * 某个方块某个面的可抽取存储。
         *
         * @param slot 方块
         * @param face 面
         * @return 存储；没有时为 {@code null}
         */
        @Nullable
        MEStorage storage(Slot slot, Direction face) {
            return this.owner.storage(slot.level(), slot.pos(), face);
        }

        /**
         * 阻挡判定：任何一个方块的任何一个面上还有这台机器的样板输入就整单拒绝。
         *
         * @param inputs 这台机器所有样板输入的 key 并集（已去组件）
         * @return 是否撞到阻挡
         */
        boolean blockingHit(Set<AEKey> inputs) {
            if (inputs.isEmpty()) {
                return false;
            }
            for (int slotIndex = 0; slotIndex < this.slots.size(); slotIndex++) {
                for (Direction face : FACES) {
                    PatternProviderTarget target = this.target(slotIndex, face);
                    if (target != null && target.containsPatternInput(inputs)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * 给一个 key 选面：类型方块优先、规则面优先，其次六面模拟，要求至少能收 1 个。
         *
         * @param key    资源
         * @param amount 这一份的数量
         * @return 能收的目标；一个都收不了时为 {@code null}
         */
        @Nullable
        Target select(AEKey key, long amount) {
            Direction preferred = this.owner.ruleFace(key);
            for (int slotIndex = 0; slotIndex < this.slots.size(); slotIndex++) {
                if (preferred != null
                        && this.accepts(slotIndex, preferred, key, amount)) {
                    return new Target(this.slots.get(slotIndex), preferred,
                            this.target(slotIndex, preferred));
                }
                for (Direction face : FACES) {
                    if (face == preferred) {
                        continue;
                    }
                    if (this.accepts(slotIndex, face, key, amount)) {
                        return new Target(this.slots.get(slotIndex), face,
                                this.target(slotIndex, face));
                    }
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
        long insert(Target target, AEKey key, long amount, Actionable mode) {
            return target.target().insert(key, amount, mode);
        }

        /** 这个方块这个面模拟插入是否至少收 1 个。 */
        private boolean accepts(int slotIndex, Direction face, AEKey key, long amount) {
            PatternProviderTarget target = this.target(slotIndex, face);
            return target != null && target.insert(key, amount, Actionable.SIMULATE) > 0L;
        }
    }
}
