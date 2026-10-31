package io.github.crafteve.idlefactory.factory.diag;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryRuntime;
import io.github.crafteve.idlefactory.factory.io.DiagEvent;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.machine.MachineIoGroup;
import io.github.crafteve.idlefactory.factory.machine.MachineRef;
import io.github.crafteve.idlefactory.factory.probe.Health;
import io.github.crafteve.idlefactory.factory.observation.Observation;
import io.github.crafteve.idlefactory.factory.observation.ObservationService;
import io.github.crafteve.idlefactory.factory.resource.ResourceKey;
import io.github.crafteve.idlefactory.factory.resource.ResourceKind;
import io.github.crafteve.idlefactory.factory.resource.ResourceSpec;
import io.github.crafteve.idlefactory.factory.storage.Storage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 机器体检：回答「这台机器为什么不动」。
 *
 * <p>现场重新模拟一遍，不回放历史。按资源类型取对应的方块能力，判定顺序固定：
 * 机器还在吗 → 区块加载了吗 → 面有能力吗 → 仓库有料吗 → 机器收得下吗 → 输出槽有东西吗。</p>
 *
 * <p>P1 删除产线后体检直接以机器为粒度：{@link #checkAll} 给出整个机器库，
 * {@link #check} 只给一台。</p>
 *
 * <p>「槽满」是 warning 不是「空闲」：没读机器状态就不能假设它在加工——塞满煤炭却不工作的
 * 熔炉和正在烧的熔炉，从 IO 这一侧看是一样的。</p>
 */
public final class DiagChecks {
    private DiagChecks() {
        // 纯静态工具类
    }

    /**
     * 一台机器的体检结果。
     *
     * @param machineId 机器 id
     * @param level     等级
     * @param detail    一句话结论
     * @param problems  全部问题；{@code detail} 只取第一条，悬停能看完整
     */
    public record GroupCheck(UUID machineId, DiagEvent.Level level, String detail, List<String> problems) {
    }

    /**
     * 体检整个机器库。
     *
     * @param server  服务端
     * @param factory 工厂
     * @return 每台机器一条结果
     */
    public static List<GroupCheck> checkAll(MinecraftServer server, Factory factory) {
        List<GroupCheck> result = new ArrayList<>();
        Storage storage = factory.live().storage();
        boolean online = factory.live().storageOnline();
        FactoryRuntime runtime = FactoryRuntime.get();
        ObservationService observations = runtime == null ? null : runtime.observations();
        for (Machine entry : factory.config().machines().all()) {
            result.add(checkGroup(server, storage, online, entry.ref(), entry.io(), observations));
        }
        return result;
    }

    /**
     * 体检一台机器。
     *
     * @param server    服务端
     * @param factory   工厂
     * @param machineId 机器 id
     * @return 结果；机器不在机器库时为一条 ERROR
     */
    public static GroupCheck check(MinecraftServer server, Factory factory, UUID machineId) {
        Optional<Machine> target = factory.config().machines().byId(machineId);
        if (target.isEmpty()) {
            return simple(machineId, DiagEvent.Level.ERROR, "机器已不在机器库");
        }
        Machine entry = target.get();
        FactoryRuntime runtime = FactoryRuntime.get();
        return checkGroup(server, factory.live().storage(), factory.live().storageOnline(),
                entry.ref(), entry.io(), runtime == null ? null : runtime.observations());
    }

    private static GroupCheck checkGroup(MinecraftServer server, Storage storage, boolean online,
            MachineRef machine, MachineIoGroup group, @Nullable ObservationService observations) {
        ServerLevel level = server.getLevel(machine.dimension());
        if (level == null) {
            return simple(machine.id(), DiagEvent.Level.ERROR, "维度不存在");
        }
        if (observations != null) {
            // why 是现场体检：先刷新这一台，再看结论
            Observation observation = observations.refresh(machine.blockKey());
            if (observation.health() == Health.UNLOADED) {
                return simple(machine.id(), DiagEvent.Level.WARN, "区块未加载");
            }
            if (observation.health() != Health.PRESENT) {
                return simple(machine.id(), DiagEvent.Level.ERROR, observation.health().describe());
            }
        } else if (!level.isLoaded(machine.pos())) {
            return simple(machine.id(), DiagEvent.Level.WARN, "区块未加载");
        }

        List<String> problems = new ArrayList<>();
        DiagEvent.Level worst = DiagEvent.Level.OK;
        boolean outputIdle = false;

        if (!online && (!group.inputs().isEmpty() || !group.outputs().isEmpty())) {
            worst = DiagEvent.Level.WARN;
            problems.add("仓库离线");
        }

        for (ResourceSpec spec : group.inputs()) {
            DiagEvent.Level level2 = checkInput(level, storage, online, machine, spec, problems);
            worst = worse(worst, level2);
        }
        for (ResourceSpec spec : group.outputs()) {
            CheckResult check = checkOutput(level, storage, online, machine, spec, problems);
            worst = worse(worst, check.level());
            outputIdle |= check.idle();
        }

        if (worst != DiagEvent.Level.OK) {
            String detail = problems.get(0);
            if (problems.size() > 1) {
                detail = detail + "，等 " + problems.size() + " 处";
            }
            return new GroupCheck(machine.id(), worst, detail, List.copyOf(problems));
        }
        if (group.inputs().isEmpty() && group.outputs().isEmpty()) {
            return simple(machine.id(), DiagEvent.Level.IDLE, "没有配置输入输出");
        }
        if (outputIdle) {
            return simple(machine.id(), DiagEvent.Level.IDLE, "空闲（机器还没产出）");
        }
        return simple(machine.id(), DiagEvent.Level.OK, "就绪");
    }

    private static DiagEvent.Level checkInput(ServerLevel level, Storage storage, boolean online,
            MachineRef machine, ResourceSpec spec, List<String> problems) {
        return switch (spec.key().kind()) {
            case ITEM -> checkItemInput(level, storage, online, machine, spec, problems);
            case FLUID -> checkFluidInput(level, storage, online, machine, spec, problems);
            case ENERGY -> checkEnergyInput(level, storage, online, machine, spec, problems);
            case CHEMICAL -> {
                problems.add("暂不支持化学品");
                yield DiagEvent.Level.ERROR;
            }
        };
    }

    private static CheckResult checkOutput(ServerLevel level, Storage storage, boolean online,
            MachineRef machine, ResourceSpec spec, List<String> problems) {
        return switch (spec.key().kind()) {
            case ITEM -> checkItemOutput(level, storage, online, machine, spec, problems);
            case FLUID -> checkFluidOutput(level, storage, online, machine, spec, problems);
            case ENERGY -> checkEnergyOutput(level, storage, online, machine, spec, problems);
            case CHEMICAL -> {
                problems.add("暂不支持化学品");
                yield new CheckResult(DiagEvent.Level.ERROR, false);
            }
        };
    }

    // ------------------------------------------------------------------
    // 物品
    // ------------------------------------------------------------------

    private static DiagEvent.Level checkItemInput(ServerLevel level, Storage storage, boolean online,
            MachineRef machine, ResourceSpec spec, List<String> problems) {
        ItemStack preview = itemSample(spec.key());
        if (preview.isEmpty()) {
            problems.add("物品 id 无法解析：" + spec.key().id());
            return DiagEvent.Level.ERROR;
        }
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, machine.pos(), spec.face());
        if (handler == null) {
            problems.add(faceLabel(spec) + "没有物品能力");
            return DiagEvent.Level.ERROR;
        }
        if (online && !storage.supports(ResourceKind.ITEM)) {
            problems.add("仓库不支持物品");
            return DiagEvent.Level.WARN;
        }
        if (online && !hasItem(storage, spec)) {
            problems.add("仓库没有 " + shortName(spec.key()));
            return DiagEvent.Level.WARN;
        }
        // 模糊规则的容量按「无组件样本」估算：多数机器的槽位过滤与组件无关，够用且不扫全仓。
        if (itemCapacity(handler, preview) <= 0) {
            problems.add(itemContains(handler, preview)
                    ? "输入槽已满，收不下 " + shortName(spec.key())
                    : "机器不接受 " + shortName(spec.key()));
            return DiagEvent.Level.WARN;
        }
        return DiagEvent.Level.OK;
    }

    private static CheckResult checkItemOutput(ServerLevel level, Storage storage, boolean online,
            MachineRef machine, ResourceSpec spec, List<String> problems) {
        ItemStack preview = itemSample(spec.key());
        if (preview.isEmpty()) {
            problems.add("物品 id 无法解析：" + spec.key().id());
            return new CheckResult(DiagEvent.Level.ERROR, false);
        }
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, machine.pos(), spec.face());
        if (handler == null) {
            problems.add(faceLabel(spec) + "没有物品能力");
            return new CheckResult(DiagEvent.Level.ERROR, false);
        }
        if (!containsItem(handler, spec)) {
            return new CheckResult(DiagEvent.Level.OK, true);
        }
        if (online && storage.insert(spec.key(), 1L, true) <= 0L) {
            problems.add("仓库不收 " + shortName(spec.key()));
            return new CheckResult(DiagEvent.Level.WARN, false);
        }
        return new CheckResult(DiagEvent.Level.OK, false);
    }

    // ------------------------------------------------------------------
    // 流体
    // ------------------------------------------------------------------

    private static DiagEvent.Level checkFluidInput(ServerLevel level, Storage storage, boolean online,
            MachineRef machine, ResourceSpec spec, List<String> problems) {
        Fluid fluid = BuiltInRegistries.FLUID.get(spec.key().id());
        if (fluid == Fluids.EMPTY) {
            problems.add("流体 id 无法解析：" + spec.key().id());
            return DiagEvent.Level.ERROR;
        }
        IFluidHandler handler = level.getCapability(Capabilities.FluidHandler.BLOCK, machine.pos(), spec.face());
        if (handler == null) {
            problems.add(faceLabel(spec) + "没有流体能力");
            return DiagEvent.Level.ERROR;
        }
        if (online && !storage.supports(ResourceKind.FLUID)) {
            problems.add("仓库不支持流体");
            return DiagEvent.Level.WARN;
        }
        if (online && storage.extract(spec.key(), 1L, true) <= 0L) {
            problems.add("仓库没有 " + fluidName(fluid));
            return DiagEvent.Level.WARN;
        }
        if (fluidCapacity(handler, fluid) <= 0) {
            problems.add(fluidContains(handler, fluid)
                    ? "输入槽已满，收不下 " + fluidName(fluid)
                    : "机器不接受 " + fluidName(fluid));
            return DiagEvent.Level.WARN;
        }
        return DiagEvent.Level.OK;
    }

    private static CheckResult checkFluidOutput(ServerLevel level, Storage storage, boolean online,
            MachineRef machine, ResourceSpec spec, List<String> problems) {
        Fluid fluid = BuiltInRegistries.FLUID.get(spec.key().id());
        if (fluid == Fluids.EMPTY) {
            problems.add("流体 id 无法解析：" + spec.key().id());
            return new CheckResult(DiagEvent.Level.ERROR, false);
        }
        IFluidHandler handler = level.getCapability(Capabilities.FluidHandler.BLOCK, machine.pos(), spec.face());
        if (handler == null) {
            problems.add(faceLabel(spec) + "没有流体能力");
            return new CheckResult(DiagEvent.Level.ERROR, false);
        }
        if (!fluidContains(handler, fluid)) {
            return new CheckResult(DiagEvent.Level.OK, true);
        }
        if (online && storage.insert(spec.key(), 1L, true) <= 0L) {
            problems.add("仓库不收 " + fluidName(fluid));
            return new CheckResult(DiagEvent.Level.WARN, false);
        }
        return new CheckResult(DiagEvent.Level.OK, false);
    }

    // ------------------------------------------------------------------
    // 能量
    // ------------------------------------------------------------------

    private static DiagEvent.Level checkEnergyInput(ServerLevel level, Storage storage, boolean online,
            MachineRef machine, ResourceSpec spec, List<String> problems) {
        IEnergyStorage handler = energyReceiver(level, machine.pos(), spec.face());
        if (handler == null) {
            problems.add(faceLabel(spec) + "没有能量能力");
            return DiagEvent.Level.ERROR;
        }
        if (online && !storage.supports(ResourceKind.ENERGY)) {
            problems.add("仓库不支持 FE");
            return DiagEvent.Level.WARN;
        }
        if (online && storage.extract(ResourceKey.ENERGY, 1000L, true) <= 0L) {
            problems.add("仓库没有 FE");
            return DiagEvent.Level.WARN;
        }
        if (handler.getMaxEnergyStored() - handler.getEnergyStored() <= 0) {
            problems.add("能量已满");
            return DiagEvent.Level.WARN;
        }
        if (handler.receiveEnergy(1000, true) <= 0) {
            problems.add("机器不收 FE");
            return DiagEvent.Level.WARN;
        }
        return DiagEvent.Level.OK;
    }

    /**
     * 找机器的能量接收面。
     *
     * <p>Mekanism 的 {@code canReceive} 恒 true，真正的面限制在按面筛选后的 handler 里，
     * 所以用「模拟接收 1000 FE」探测。</p>
     */
    @Nullable
    private static IEnergyStorage energyReceiver(ServerLevel level, BlockPos pos, @Nullable Direction face) {
        if (face != null) {
            return level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, face);
        }
        for (Direction side : Direction.values()) {
            IEnergyStorage handler = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, side);
            if (handler != null && handler.receiveEnergy(1000, true) > 0) {
                return handler;
            }
        }
        return level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, null);
    }

    private static CheckResult checkEnergyOutput(ServerLevel level, Storage storage, boolean online,
            MachineRef machine, ResourceSpec spec, List<String> problems) {
        IEnergyStorage handler = level.getCapability(Capabilities.EnergyStorage.BLOCK, machine.pos(), spec.face());
        if (handler == null) {
            problems.add(faceLabel(spec) + "没有能量能力");
            return new CheckResult(DiagEvent.Level.ERROR, false);
        }
        if (handler.getEnergyStored() <= 0) {
            return new CheckResult(DiagEvent.Level.OK, true);
        }
        if (online && storage.insert(ResourceKey.ENERGY, 1000L, true) <= 0L) {
            problems.add("仓库不收 FE");
            return new CheckResult(DiagEvent.Level.WARN, false);
        }
        return new CheckResult(DiagEvent.Level.OK, false);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static DiagEvent.Level worse(DiagEvent.Level a, DiagEvent.Level b) {
        return ordinal(a) >= ordinal(b) ? a : b;
    }

    private static int ordinal(DiagEvent.Level level) {
        return switch (level) {
            case OK -> 0;
            case IDLE -> 1;
            case WARN -> 2;
            case ERROR -> 3;
        };
    }

    private static GroupCheck simple(UUID machineId, DiagEvent.Level level, String detail) {
        return new GroupCheck(machineId, level, detail, List.of(detail));
    }

    private static String faceLabel(ResourceSpec spec) {
        return spec.face() == null ? "面 any " : "面 " + spec.face().getName() + " ";
    }

    private static ItemStack itemSample(ResourceKey key) {
        return key.toStack();
    }

    /**
     * 仓库里是否有这条规则能匹配的东西。
     *
     * <p>精确规则直接模拟抽 1 个；模糊规则要扫快照看同 id 的任意变体，避免把「只有带组件
     * 变体」误报成缺料。</p>
     */
    private static boolean hasItem(Storage storage, ResourceSpec spec) {
        if (!spec.fuzzy()) {
            return storage.extract(spec.key(), 1L, true) > 0L;
        }
        for (ResourceKey key : storage.snapshot().keySet()) {
            if (key.kind() == ResourceKind.ITEM && key.id().equals(spec.key().id())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 机器里是否有这条规则能匹配的东西。
     *
     * <p>模糊规则按注册名比较；精确规则比较完整组件。</p>
     */
    private static boolean containsItem(IItemHandler handler, ResourceSpec spec) {
        if (!spec.fuzzy()) {
            return itemContains(handler, itemSample(spec.key()));
        }
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack inSlot = handler.getStackInSlot(slot);
            if (!inSlot.isEmpty() && ResourceKey.item(inSlot).id().equals(spec.key().id())) {
                return true;
            }
        }
        return false;
    }

    private static int itemCapacity(IItemHandler handler, ItemStack preview) {
        int total = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack inSlot = handler.getStackInSlot(slot);
            if (!inSlot.isEmpty() && !ItemStack.isSameItemSameComponents(inSlot, preview)) {
                continue;
            }
            int limit = Math.min(handler.getSlotLimit(slot), preview.getMaxStackSize());
            total += Math.max(0, limit - inSlot.getCount());
        }
        return total;
    }

    private static boolean itemContains(IItemHandler handler, ItemStack preview) {
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack inSlot = handler.getStackInSlot(slot);
            if (!inSlot.isEmpty() && ItemStack.isSameItemSameComponents(inSlot, preview)) {
                return true;
            }
        }
        return false;
    }

    private static int fluidCapacity(IFluidHandler handler, Fluid fluid) {
        int total = 0;
        for (int tank = 0; tank < handler.getTanks(); tank++) {
            FluidStack inTank = handler.getFluidInTank(tank);
            if (!inTank.isEmpty() && !inTank.is(fluid)) {
                continue;
            }
            total += Math.max(0, handler.getTankCapacity(tank) - inTank.getAmount());
        }
        return total;
    }

    private static boolean fluidContains(IFluidHandler handler, Fluid fluid) {
        for (int tank = 0; tank < handler.getTanks(); tank++) {
            FluidStack inTank = handler.getFluidInTank(tank);
            if (!inTank.isEmpty() && inTank.is(fluid)) {
                return true;
            }
        }
        return false;
    }

    private static String shortName(ResourceKey key) {
        String id = key.id().toString();
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    private static String fluidName(Fluid fluid) {
        String id = BuiltInRegistries.FLUID.getKey(fluid).toString();
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    /** 输出检查的附带信息：是否只是还没产出。 */
    private record CheckResult(DiagEvent.Level level, boolean idle) {
    }
}
