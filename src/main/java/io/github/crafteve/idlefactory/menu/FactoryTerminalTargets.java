package io.github.crafteve.idlefactory.menu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.stacks.AEItemKey;
import appeng.helpers.patternprovider.PatternContainer;

import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryRuntime;
import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.machine.MachineMode;
import io.github.crafteve.idlefactory.factory.machine.MachineProvider;
import io.github.crafteve.idlefactory.factory.node.MachineNode;
import io.github.crafteve.idlefactory.factory.observation.ObservationService;
import io.github.crafteve.idlefactory.factory.probe.Health;
import io.github.crafteve.idlefactory.factory.storage.MeAnchor;
import io.github.crafteve.idlefactory.integration.ae2.Ae2Binder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/**
 * 主动样板视图的目标表：工厂自己的虚拟机器，以及同一 ME 网络上 AE2 的样板容器。
 *
 * <p>目标是运行时视图，不写档；选中状态跟着菜单走，切模式重开菜单就重置。键用字符串
 * 而不是把两套身份硬塞进一个 UUID：机器是 {@code machine:<uuid>}，AE2 设备用它在 JVM
 * 里的身份哈希，保证同一次运行内刷新列表后仍能选中同一个容器。</p>
 */
public final class FactoryTerminalTargets {
    /** 机器目标的键前缀。 */
    public static final String MACHINE_PREFIX = "machine:";
    /** AE2 原生目标的键前缀。 */
    public static final String AE2_PREFIX = "ae2:";

    /** 一条目标摘要。 */
    public record Entry(FactoryTerminalTargetKind kind, String key, String name, ItemStack icon,
            int capacity, int used) {
    }

    /**
     * 单个目标的详情。
     *
     * @param mode 机器模式；AE2 原生样板容器没有这个概念，为 {@code null}
     */
    public record Detail(FactoryTerminalTargetKind kind, String key, String name,
            @Nullable MachineMode mode, boolean online, int capacity, int used) {
    }

    private final Factory factory;
    private final ServerPlayer player;
    private String selectedKey = "";

    /**
     * @param factory 拥有者工厂
     * @param player  打开终端的玩家
     */
    public FactoryTerminalTargets(Factory factory, ServerPlayer player) {
        this.factory = factory;
        this.player = player;
    }

    /** 当前选中的目标键；没有选中时为空串。 */
    public String selectedKey() {
        return this.selectedKey;
    }

    /**
     * 选中一个目标；键不存在时保持原选择。
     *
     * @param key 目标键
     */
    public void select(String key) {
        if (this.detail(key) != null) {
            this.selectedKey = key;
        }
    }

    /**
     * 当前全部目标：先机器库，再 AE2 原生样板容器。
     *
     * @return 目标摘要
     */
    public List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        entries.addAll(this.machineEntries());
        for (PatternContainer container : this.ae2Containers()) {
            InternalInventory inventory = container.getTerminalPatternInventory();
            PatternContainerGroup group = container.getTerminalGroup();
            entries.add(new Entry(FactoryTerminalTargetKind.AE2_PROVIDER,
                    keyOf(container), group.name().getString(), iconOf(group),
                    inventory.size(), usedSlots(inventory)));
        }
        return entries;
    }

    /**
     * 解析一个目标的详情。
     *
     * @param key 目标键
     * @return 详情；键不属于当前目标表时为 {@code null}
     */
    @Nullable
    public Detail detail(String key) {
        if (key == null) {
            return null;
        }
        if (key.startsWith(MACHINE_PREFIX)) {
            return this.machineDetail(key);
        }
        if (key.startsWith(AE2_PREFIX)) {
            return this.ae2Detail(key);
        }
        return null;
    }

    /**
     * 目标背后的样板库存；分页视图用它做委托。
     *
     * @param key 目标键
     * @return 库存；目标不存在或节点没建出来时为 {@code null}
     */
    @Nullable
    public InternalInventory inventoryOf(String key) {
        if (key == null) {
            return null;
        }
        if (key.startsWith(MACHINE_PREFIX)) {
            Optional<Machine> machine = this.factory.config().machines()
                    .byId(parseMachineId(key.substring(MACHINE_PREFIX.length())));
            FactoryRuntime runtime = FactoryRuntime.get();
            if (machine.isEmpty() || runtime == null) {
                return null;
            }
            MachineNode node = runtime.nodeManager().node(machine.get().id());
            return node == null ? null : node.getTerminalPatternInventory();
        }
        for (PatternContainer container : this.ae2Containers()) {
            if (keyOf(container).equals(key)) {
                return container.getTerminalPatternInventory();
            }
        }
        return null;
    }

    /**
     * 机器目标的稳定键。
     *
     * @param machineId 机器 id
     * @return 键
     */
    public static String keyOf(UUID machineId) {
        return MACHINE_PREFIX + machineId;
    }

    /**
     * AE2 目标的稳定键：JVM 内身份哈希，重启后重算。
     *
     * @param container 样板容器
     * @return 键
     */
    private static String keyOf(PatternContainer container) {
        return AE2_PREFIX + Integer.toHexString(System.identityHashCode(container));
    }

    private List<Entry> machineEntries() {
        List<Entry> entries = new ArrayList<>();
        FactoryRuntime runtime = FactoryRuntime.get();
        ObservationService observations = runtime == null ? null : runtime.observations();
        long now = this.player.serverLevel().getGameTime();
        for (FactorySnapshotBuilder.MachineView view : FactorySnapshotBuilder.machines(
                this.factory, observations, now)) {
            Machine machine = this.factory.config().machines().byId(view.ref().id()).orElse(null);
            if (machine == null) {
                continue;
            }
            MachineProvider provider = machine.provider();
            entries.add(new Entry(FactoryTerminalTargetKind.MACHINE, keyOf(machine.id()),
                    nameOf(view.blockId(), machine), icon(view.blockId()), provider.capacity(),
                    provider.patternCount()));
        }
        return entries;
    }

    @Nullable
    private Detail machineDetail(String key) {
        Optional<Machine> found = this.factory.config().machines()
                .byId(parseMachineId(key.substring(MACHINE_PREFIX.length())));
        if (found.isEmpty()) {
            return null;
        }
        Machine machine = found.get();
        MachineProvider provider = machine.provider();
        boolean online = this.isMachineOnline(machine);
        return new Detail(FactoryTerminalTargetKind.MACHINE, key, machineName(machine),
                provider.mode(), online, provider.capacity(), provider.patternCount());
    }

    @Nullable
    private Detail ae2Detail(String key) {
        for (PatternContainer container : this.ae2Containers()) {
            if (!keyOf(container).equals(key)) {
                continue;
            }
            InternalInventory inventory = container.getTerminalPatternInventory();
            return new Detail(FactoryTerminalTargetKind.AE2_PROVIDER, key,
                    container.getTerminalGroup().name().getString(), null, true,
                    inventory.size(), usedSlots(inventory));
        }
        return null;
    }

    /**
     * AE2 原生样板容器：同一 ME 网络上、终端里可见的样板容器，跳过我们自己的机器节点。
     *
     * @return 按 AE2 终端排序规则排好的容器
     */
    private List<PatternContainer> ae2Containers() {
        IGrid grid = this.grid();
        if (grid == null) {
            return List.of();
        }
        List<PatternContainer> containers = new ArrayList<>();
        try {
            for (PatternContainer container : grid.getActiveMachines(PatternContainer.class)) {
                if (container instanceof MachineNode || !container.isVisibleInTerminal()) {
                    continue;
                }
                containers.add(container);
            }
        } catch (RuntimeException e) {
            // 网格正在拆分 / 节点刚被销毁，读路径降级成空列表。
            return List.of();
        }
        containers.sort(Comparator
                .comparingLong(PatternContainer::getTerminalSortOrder)
                .thenComparing(container -> container.getTerminalGroup().name().getString()));
        return containers;
    }

    /**
     * 当前工厂锚点所在的 ME 网络。
     *
     * @return 网格；锚点没加载或没有节点时为 {@code null}
     */
    @Nullable
    private IGrid grid() {
        if (!(this.factory.config().storageAnchor() instanceof MeAnchor anchor)) {
            return null;
        }
        MinecraftServer server = this.player.getServer();
        if (server == null) {
            return null;
        }
        ServerLevel level = server.getLevel(anchor.dimension());
        if (level == null) {
            return null;
        }
        IGridNode node = Ae2Binder.nodeAt(level, anchor.pos());
        if (node == null) {
            return null;
        }
        try {
            return node.getGrid();
        } catch (RuntimeException e) {
            // 节点刚被销毁。
            return null;
        }
    }

    private boolean isMachineOnline(Machine machine) {
        FactoryRuntime runtime = FactoryRuntime.get();
        ObservationService observations = runtime == null ? null : runtime.observations();
        if (observations == null) {
            return false;
        }
        return observations.observe(machine.ref().blockKey()).health() == Health.PRESENT;
    }

    /**
     * 解析机器 id；非法字符串返回全零 uuid，查表自然查不到。
     *
     * @param raw 字符串
     * @return 机器 id
     */
    private static UUID parseMachineId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return new UUID(0L, 0L);
        }
    }

    /**
     * 取机器当前的显示名；观测缺失时退回短 id。
     *
     * @param machine 机器
     * @return 名称
     */
    private String machineName(Machine machine) {
        FactoryRuntime runtime = FactoryRuntime.get();
        ObservationService observations = runtime == null ? null : runtime.observations();
        long now = this.player.serverLevel().getGameTime();
        for (FactorySnapshotBuilder.MachineView view : FactorySnapshotBuilder.machines(
                this.factory, observations, now)) {
            if (view.ref().id().equals(machine.id())) {
                return nameOf(view.blockId(), machine);
            }
        }
        return "机器 " + machine.ref().shortId();
    }

    /**
     * 方块 id 对应的显示名。
     *
     * @param blockId 观测到的方块 id
     * @param machine 机器
     * @return 方块名；观测缺失时退回短 id
     */
    private static String nameOf(@Nullable ResourceLocation blockId, Machine machine) {
        if (blockId == null) {
            return "机器 " + machine.ref().shortId();
        }
        Block block = BuiltInRegistries.BLOCK.get(blockId);
        return block.getName().getString();
    }

    /**
     * 方块 id 对应的物品图标。
     *
     * @param blockId 方块 id
     * @return 图标；未知时为空栈
     */
    private static ItemStack icon(@Nullable ResourceLocation blockId) {
        if (blockId == null) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(BuiltInRegistries.BLOCK.get(blockId).asItem());
    }

    /**
     * AE2 容器组的图标。
     *
     * @param group 容器组
     * @return 物品图标；组没有图标时为空栈
     */
    private static ItemStack iconOf(PatternContainerGroup group) {
        AEItemKey icon = group.icon();
        return icon == null ? ItemStack.EMPTY : icon.getReadOnlyStack();
    }

    /**
     * 已放样板的槽数。
     *
     * @param inventory 样板库存
     * @return 非空槽数
     */
    private static int usedSlots(InternalInventory inventory) {
        int used = 0;
        for (int i = 0; i < inventory.size(); i++) {
            if (!inventory.getStackInSlot(i).isEmpty()) {
                used++;
            }
        }
        return used;
    }
}
