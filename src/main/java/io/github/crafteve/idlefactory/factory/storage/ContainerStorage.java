package io.github.crafteve.idlefactory.factory.storage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.resource.ResourceKey;
import io.github.crafteve.idlefactory.factory.resource.ResourceKind;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

/**
 * 把一组方块容器包成工厂的 {@link Storage}，支持物品、流体、FE。
 *
 * <p>物品规则：按优先级分组，组内两阶段（先补满已有堆叠、再占空槽），抽取先抽不满的堆。
 * 流体同构：先补已有同种流体的槽，再找空槽。能量没有槽的概念，按容器顺序充放。</p>
 *
 * <p>满堆判断是 {@code count >= min(getSlotLimit, getMaxStackSize)}，不写死 64。</p>
 *
 * <p>容器每次现查能力，不缓存到字段；一次 insert/extract 调用内会解析一次复用。</p>
 */
public final class ContainerStorage implements Storage {
    /**
     * 能量面探测用的模拟量。
     *
     * <p>Mekanism 的 FE↔J 换算会在小数值上截断：模拟抽取 1 FE 时，转成 J 再转回 FE 可能是 0，
     * 看起来像「不能抽」。用 1000 FE 探测可以跨过截断。</p>
     */
    private static final int ENERGY_PROBE = 1000;

    private final MinecraftServer server;
    /** 可用容器，已按优先级从高到低排序。 */
    private final List<ContainerRef> containers;
    /** 绑定时声明的容器总数（含当前不可用的）。 */
    private final int totalContainers;

    /**
     * @param server          服务端
     * @param containers      可用容器，按优先级从高到低
     * @param totalContainers 声明的容器总数
     */
    public ContainerStorage(MinecraftServer server, List<ContainerRef> containers, int totalContainers) {
        this.server = server;
        this.containers = List.copyOf(containers);
        this.totalContainers = totalContainers;
    }

    /** 声明的容器总数。 */
    public int containerCount() {
        return this.totalContainers;
    }

    /** 当前可用的容器数。 */
    public int availableCount() {
        return this.containers.size();
    }

    @Override
    public long insert(ResourceKey key, long amount, boolean simulate) {
        if (amount <= 0L) {
            return 0L;
        }
        return switch (key.kind()) {
            case ITEM -> insertItem(key, amount, simulate);
            case FLUID -> insertFluid(key, amount, simulate);
            case ENERGY -> insertEnergy(amount, simulate);
            case CHEMICAL -> 0L;
        };
    }

    @Override
    public long extract(ResourceKey key, long amount, boolean simulate) {
        if (amount <= 0L) {
            return 0L;
        }
        return switch (key.kind()) {
            case ITEM -> extractItem(key, amount, simulate);
            case FLUID -> extractFluid(key, amount, simulate);
            case ENERGY -> extractEnergy(amount, simulate);
            case CHEMICAL -> 0L;
        };
    }

    @Override
    public boolean supports(ResourceKind kind) {
        return kind != ResourceKind.CHEMICAL;
    }

    @Override
    public boolean isEmpty() {
        for (ContainerRef ref : this.containers) {
            if (!isEmptyContainer(ref)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Map<ResourceKey, Long> snapshot() {
        Map<ResourceKey, Long> result = new LinkedHashMap<>();
        for (ContainerRef ref : this.containers) {
            IItemHandler items = itemHandler(this.server, ref);
            if (items != null) {
                for (int slot = 0; slot < items.getSlots(); slot++) {
                    ItemStack stack = items.getStackInSlot(slot);
                    if (!stack.isEmpty()) {
                        ResourceKey key = ResourceKey.item(stack);
                        result.merge(key, (long) stack.getCount(), Long::sum);
                    }
                }
            }
            IFluidHandler fluids = fluidHandler(this.server, ref);
            if (fluids != null) {
                for (int tank = 0; tank < fluids.getTanks(); tank++) {
                    FluidStack fluid = fluids.getFluidInTank(tank);
                    if (!fluid.isEmpty()) {
                        result.merge(ResourceKey.fluid(fluid), (long) fluid.getAmount(), Long::sum);
                    }
                }
            }
            IEnergyStorage energy = energyHandler(this.server, ref);
            if (energy != null && energy.getEnergyStored() > 0) {
                result.merge(ResourceKey.ENERGY, (long) energy.getEnergyStored(), Long::sum);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 物品
    // ------------------------------------------------------------------

    private long insertItem(ResourceKey key, long amount, boolean simulate) {
        ItemStack stack = itemSample(key);
        if (stack.isEmpty()) {
            return 0L;
        }
        long remaining = amount;
        int index = 0;
        while (index < this.containers.size() && remaining > 0L) {
            int priority = this.containers.get(index).priority();
            List<ContainerRef> group = new ArrayList<>();
            while (index < this.containers.size() && this.containers.get(index).priority() == priority) {
                group.add(this.containers.get(index));
                index++;
            }
            remaining = insertItemIntoGroup(group, stack, remaining, simulate);
        }
        return amount - remaining;
    }

    private long insertItemIntoGroup(List<ContainerRef> group, ItemStack stack, long amount, boolean simulate) {
        long remaining = amount;
        List<IItemHandler> handlers = new ArrayList<>(group.size());
        for (ContainerRef ref : group) {
            IItemHandler handler = itemHandler(this.server, ref);
            if (handler != null) {
                handlers.add(handler);
            }
        }

        // 阶段 1：补满本组已有堆叠
        for (IItemHandler handler : handlers) {
            for (int slot = 0; slot < handler.getSlots() && remaining > 0L; slot++) {
                ItemStack inSlot = handler.getStackInSlot(slot);
                if (inSlot.isEmpty() || !ItemStack.isSameItem(inSlot, stack)) {
                    continue;
                }
                int limit = Math.min(handler.getSlotLimit(slot), inSlot.getMaxStackSize());
                int room = limit - inSlot.getCount();
                if (room <= 0) {
                    continue;
                }
                int attemptCount = (int) Math.min(remaining, room);
                ItemStack left = handler.insertItem(slot, stack.copyWithCount(attemptCount), simulate);
                remaining -= attemptCount - left.getCount();
            }
            if (remaining <= 0L) {
                return 0L;
            }
        }

        // 阶段 2：占本组空槽
        for (IItemHandler handler : handlers) {
            for (int slot = 0; slot < handler.getSlots() && remaining > 0L; slot++) {
                ItemStack inSlot = handler.getStackInSlot(slot);
                if (!inSlot.isEmpty() && ItemStack.isSameItem(inSlot, stack)) {
                    continue;
                }
                int limit = Math.min(handler.getSlotLimit(slot), stack.getMaxStackSize());
                if (limit <= 0) {
                    continue;
                }
                int attemptCount = (int) Math.min(remaining, limit);
                ItemStack left = handler.insertItem(slot, stack.copyWithCount(attemptCount), simulate);
                remaining -= attemptCount - left.getCount();
            }
            if (remaining <= 0L) {
                return 0L;
            }
        }
        return remaining;
    }

    private long extractItem(ResourceKey key, long amount, boolean simulate) {
        ItemStack stack = itemSample(key);
        if (stack.isEmpty()) {
            return 0L;
        }
        long taken = 0L;
        int index = this.containers.size();
        while (index > 0 && taken < amount) {
            int priority = this.containers.get(index - 1).priority();
            List<ContainerRef> group = new ArrayList<>();
            while (index > 0 && this.containers.get(index - 1).priority() == priority) {
                index--;
                group.add(this.containers.get(index));
            }
            taken += extractItemFromGroup(group, stack, amount - taken, simulate);
        }
        return taken;
    }

    private long extractItemFromGroup(List<ContainerRef> group, ItemStack stack, long amount, boolean simulate) {
        List<SlotRef> partial = new ArrayList<>();
        List<SlotRef> full = new ArrayList<>();
        for (ContainerRef ref : group) {
            IItemHandler handler = itemHandler(this.server, ref);
            if (handler == null) {
                continue;
            }
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack inSlot = handler.getStackInSlot(slot);
                if (inSlot.isEmpty() || !ItemStack.isSameItem(inSlot, stack)) {
                    continue;
                }
                int limit = Math.min(handler.getSlotLimit(slot), inSlot.getMaxStackSize());
                SlotRef slotRef = new SlotRef(handler, slot);
                if (inSlot.getCount() >= limit) {
                    full.add(slotRef);
                } else {
                    partial.add(slotRef);
                }
            }
        }
        long taken = drainItems(partial, amount, simulate);
        if (taken < amount) {
            taken += drainItems(full, amount - taken, simulate);
        }
        return taken;
    }

    private long drainItems(List<SlotRef> slots, long amount, boolean simulate) {
        long remaining = amount;
        long taken = 0L;
        for (SlotRef ref : slots) {
            if (remaining <= 0L) {
                break;
            }
            ItemStack inSlot = ref.handler().getStackInSlot(ref.slot());
            if (inSlot.isEmpty()) {
                continue;
            }
            int want = (int) Math.min(remaining, inSlot.getCount());
            ItemStack got = ref.handler().extractItem(ref.slot(), want, simulate);
            if (got.isEmpty()) {
                continue;
            }
            taken += got.getCount();
            remaining -= got.getCount();
        }
        return taken;
    }

    // ------------------------------------------------------------------
    // 流体
    // ------------------------------------------------------------------

    private long insertFluid(ResourceKey key, long amount, boolean simulate) {
        Fluid fluid = BuiltInRegistries.FLUID.get(key.id());
        if (fluid == net.minecraft.world.level.material.Fluids.EMPTY) {
            return 0L;
        }
        long remaining = amount;
        int index = 0;
        while (index < this.containers.size() && remaining > 0L) {
            int priority = this.containers.get(index).priority();
            List<ContainerRef> group = new ArrayList<>();
            while (index < this.containers.size() && this.containers.get(index).priority() == priority) {
                group.add(this.containers.get(index));
                index++;
            }
            remaining = insertFluidIntoGroup(group, fluid, remaining, simulate);
        }
        return amount - remaining;
    }

    private long insertFluidIntoGroup(List<ContainerRef> group, Fluid fluid, long amount, boolean simulate) {
        long remaining = amount;
        List<IFluidHandler> handlers = new ArrayList<>(group.size());
        for (ContainerRef ref : group) {
            IFluidHandler handler = fluidHandler(this.server, ref, fluid, false);
            if (handler != null) {
                handlers.add(handler);
            }
        }

        // 阶段 1：补已有同种流体的槽
        for (IFluidHandler handler : handlers) {
            for (int tank = 0; tank < handler.getTanks() && remaining > 0L; tank++) {
                FluidStack inTank = handler.getFluidInTank(tank);
                if (inTank.isEmpty() || !inTank.is(fluid)) {
                    continue;
                }
                int room = handler.getTankCapacity(tank) - inTank.getAmount();
                if (room <= 0) {
                    continue;
                }
                int filled = handler.fill(new FluidStack(fluid, (int) Math.min(remaining, room)), action(simulate));
                remaining -= filled;
            }
            if (remaining <= 0L) {
                return 0L;
            }
        }

        // 阶段 2：空槽
        for (IFluidHandler handler : handlers) {
            for (int tank = 0; tank < handler.getTanks() && remaining > 0L; tank++) {
                if (!handler.getFluidInTank(tank).isEmpty()) {
                    continue;
                }
                int room = handler.getTankCapacity(tank);
                if (room <= 0) {
                    continue;
                }
                int filled = handler.fill(new FluidStack(fluid, (int) Math.min(remaining, room)), action(simulate));
                remaining -= filled;
            }
            if (remaining <= 0L) {
                return 0L;
            }
        }
        return remaining;
    }

    private long extractFluid(ResourceKey key, long amount, boolean simulate) {
        Fluid fluid = BuiltInRegistries.FLUID.get(key.id());
        if (fluid == net.minecraft.world.level.material.Fluids.EMPTY) {
            return 0L;
        }
        long taken = 0L;
        int index = this.containers.size();
        while (index > 0 && taken < amount) {
            int priority = this.containers.get(index - 1).priority();
            List<ContainerRef> group = new ArrayList<>();
            while (index > 0 && this.containers.get(index - 1).priority() == priority) {
                index--;
                group.add(this.containers.get(index));
            }
            for (ContainerRef ref : group) {
                if (taken >= amount) {
                    break;
                }
                IFluidHandler handler = fluidHandler(this.server, ref, fluid, true);
                if (handler == null) {
                    continue;
                }
                FluidStack drained = handler.drain(
                        new FluidStack(fluid, (int) Math.min(amount - taken, Integer.MAX_VALUE)), action(simulate));
                taken += drained.getAmount();
            }
        }
        return taken;
    }

    // ------------------------------------------------------------------
    // 能量
    // ------------------------------------------------------------------

    private long insertEnergy(long amount, boolean simulate) {
        long remaining = amount;
        int index = 0;
        while (index < this.containers.size() && remaining > 0L) {
            int priority = this.containers.get(index).priority();
            List<ContainerRef> group = new ArrayList<>();
            while (index < this.containers.size() && this.containers.get(index).priority() == priority) {
                group.add(this.containers.get(index));
                index++;
            }
            for (ContainerRef ref : group) {
                if (remaining <= 0L) {
                    break;
                }
                IEnergyStorage handler = energyHandler(this.server, ref, false);
                if (handler == null) {
                    continue;
                }
                int accepted = handler.receiveEnergy(
                        (int) Math.min(remaining, Integer.MAX_VALUE), simulate);
                remaining -= accepted;
            }
        }
        return amount - remaining;
    }

    private long extractEnergy(long amount, boolean simulate) {
        long taken = 0L;
        int index = this.containers.size();
        while (index > 0 && taken < amount) {
            int priority = this.containers.get(index - 1).priority();
            List<ContainerRef> group = new ArrayList<>();
            while (index > 0 && this.containers.get(index - 1).priority() == priority) {
                index--;
                group.add(this.containers.get(index));
            }
            for (ContainerRef ref : group) {
                if (taken >= amount) {
                    break;
                }
                IEnergyStorage handler = energyHandler(this.server, ref, true);
                if (handler == null) {
                    continue;
                }
                taken += handler.extractEnergy(
                        (int) Math.min(amount - taken, Integer.MAX_VALUE), simulate);
            }
        }
        return taken;
    }

    // ------------------------------------------------------------------
    // 能力
    // ------------------------------------------------------------------

    private boolean isEmptyContainer(ContainerRef ref) {
        IItemHandler items = itemHandler(this.server, ref);
        if (items != null) {
            for (int slot = 0; slot < items.getSlots(); slot++) {
                if (!items.getStackInSlot(slot).isEmpty()) {
                    return false;
                }
            }
        }
        IFluidHandler fluids = fluidHandler(this.server, ref);
        if (fluids != null) {
            for (int tank = 0; tank < fluids.getTanks(); tank++) {
                if (!fluids.getFluidInTank(tank).isEmpty()) {
                    return false;
                }
            }
        }
        IEnergyStorage energy = energyHandler(this.server, ref);
        return energy == null || energy.getEnergyStored() <= 0;
    }

    /**
     * 这个容器现在是否可用（任意一种能力存在）。
     *
     * @param server 服务端
     * @param ref    容器
     * @return 可用时为 {@code true}
     */
    public static boolean available(MinecraftServer server, ContainerRef ref) {
        return itemHandler(server, ref) != null
                || fluidHandler(server, ref) != null
                || energyHandler(server, ref) != null;
    }

    @Nullable
    private static ServerLevel level(MinecraftServer server, ContainerRef ref) {
        ServerLevel level = server.getLevel(ref.dimension());
        if (level == null || !level.isLoaded(ref.pos())) {
            return null;
        }
        return level;
    }

    /**
     * 物品能力。主路径是 NeoForge 能力；{@link Container} 包装只给没注册能力的模组方块兜底。
     *
     * @param server 服务端
     * @param ref    容器
     * @return 处理器；不可用时 {@code null}
     */
    @Nullable
    public static IItemHandler itemHandler(MinecraftServer server, ContainerRef ref) {
        ServerLevel level = level(server, ref);
        if (level == null) {
            return null;
        }
        IItemHandler capability = level.getCapability(Capabilities.ItemHandler.BLOCK, ref.pos(), null);
        if (capability != null) {
            return capability;
        }
        BlockEntity blockEntity = level.getBlockEntity(ref.pos());
        if (blockEntity instanceof Container container) {
            return new InvWrapper(container);
        }
        return null;
    }

    @Nullable
    public static IFluidHandler fluidHandler(MinecraftServer server, ContainerRef ref) {
        ServerLevel level = level(server, ref);
        if (level == null) {
            return null;
        }
        IFluidHandler handler = level.getCapability(Capabilities.FluidHandler.BLOCK, ref.pos(), null);
        if (handler != null) {
            return handler;
        }
        for (Direction side : Direction.values()) {
            handler = level.getCapability(Capabilities.FluidHandler.BLOCK, ref.pos(), side);
            if (handler != null) {
                return handler;
            }
        }
        return null;
    }

    /**
     * 取一个方向明确可抽 / 可灌的流体能力。
     *
     * <p>和能量一样，Mekanism 的流体 capability 也受面配置影响，无方向查询可能拿到不能抽的
     * handler；这里逐面「模拟灌/抽 1 mB」探测。</p>
     *
     * @param server     服务端
     * @param ref        容器
     * @param fluid      流体
     * @param forExtract {@code true} 找可抽取的面，{@code false} 找可灌入的面
     * @return 流体能力；都不可用时退回无方向查询
     */
    @Nullable
    public static IFluidHandler fluidHandler(MinecraftServer server, ContainerRef ref, Fluid fluid, boolean forExtract) {
        ServerLevel level = level(server, ref);
        if (level == null) {
            return null;
        }
        FluidStack probe = new FluidStack(fluid, 1);
        for (Direction side : Direction.values()) {
            IFluidHandler handler = level.getCapability(Capabilities.FluidHandler.BLOCK, ref.pos(), side);
            if (handler == null) {
                continue;
            }
            int result = forExtract
                    ? handler.drain(probe, IFluidHandler.FluidAction.SIMULATE).getAmount()
                    : handler.fill(probe, IFluidHandler.FluidAction.SIMULATE);
            if (result > 0) {
                return handler;
            }
        }
        return fluidHandler(server, ref);
    }

    @Nullable
    public static IEnergyStorage energyHandler(MinecraftServer server, ContainerRef ref) {
        ServerLevel level = level(server, ref);
        if (level == null) {
            return null;
        }
        IEnergyStorage handler = level.getCapability(Capabilities.EnergyStorage.BLOCK, ref.pos(), null);
        if (handler != null) {
            return handler;
        }
        for (Direction side : Direction.values()) {
            handler = level.getCapability(Capabilities.EnergyStorage.BLOCK, ref.pos(), side);
            if (handler != null) {
                return handler;
            }
        }
        return null;
    }

    /**
     * 取一个方向明确可抽 / 可收的能量能力。
     *
     * <p>不能信 {@code canExtract/canReceive}：Mekanism 的 NeoForge 能量适配器两者恒为 true，
     * 真正的限制在按面筛选后的 handler 里。所以这里逐面「模拟搬 1 FE」来探测。</p>
     *
     * @param server     服务端
     * @param ref        容器
     * @param forExtract {@code true} 找可抽取的面，{@code false} 找可接收的面
     * @return 能量能力；都不可用时退回无方向查询
     */
    @Nullable
    public static IEnergyStorage energyHandler(MinecraftServer server, ContainerRef ref, boolean forExtract) {
        ServerLevel level = level(server, ref);
        if (level == null) {
            return null;
        }
        for (Direction side : Direction.values()) {
            IEnergyStorage handler = level.getCapability(Capabilities.EnergyStorage.BLOCK, ref.pos(), side);
            if (handler == null) {
                continue;
            }
            int probe = forExtract
                    ? handler.extractEnergy(ENERGY_PROBE, true)
                    : handler.receiveEnergy(ENERGY_PROBE, true);
            if (probe > 0) {
                return handler;
            }
        }
        return energyHandler(server, ref);
    }

    private static ItemStack itemSample(ResourceKey key) {
        var item = BuiltInRegistries.ITEM.get(key.id());
        if (item == net.minecraft.world.item.Items.AIR) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(item);
    }

    private static IFluidHandler.FluidAction action(boolean simulate) {
        return simulate ? IFluidHandler.FluidAction.SIMULATE : IFluidHandler.FluidAction.EXECUTE;
    }

    /** 一个候选槽。 */
    private record SlotRef(IItemHandler handler, int slot) {
    }
}
