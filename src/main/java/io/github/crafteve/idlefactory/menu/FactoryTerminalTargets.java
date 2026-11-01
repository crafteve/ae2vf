package io.github.crafteve.idlefactory.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryRuntime;
import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.machine.MachineMode;
import io.github.crafteve.idlefactory.factory.machine.MachineProvider;
import io.github.crafteve.idlefactory.factory.observation.ObservationService;
import io.github.crafteve.idlefactory.factory.probe.Health;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/**
 * 主动样板视图的目标表：工厂自己的虚拟机器，以及同一 ME 网络上 AE2 的样板容器。
 *
 * <p>目标是运行时视图，不写档；选中状态跟着菜单走，切模式重开菜单就重置。键用字符串
 * 而不是把两套身份硬塞进一个 UUID：机器是 {@code machine:<uuid>}，AE2 设备用它自己的
 * 容器标识。</p>
 */
public final class FactoryTerminalTargets {
    /** 机器目标的键前缀。 */
    public static final String MACHINE_PREFIX = "machine:";

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
     * 当前全部目标。
     *
     * @return 目标摘要，按机器库顺序
     */
    public List<Entry> entries() {
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
            ItemStack icon = icon(view.blockId());
            entries.add(new Entry(FactoryTerminalTargetKind.MACHINE, keyOf(machine.id()),
                    nameOf(view.blockId(), machine), icon, provider.capacity(),
                    provider.patternCount()));
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
        if (key == null || !key.startsWith(MACHINE_PREFIX)) {
            return null;
        }
        Optional<Machine> found = this.factory.config().machines()
                .byId(parseMachineId(key.substring(MACHINE_PREFIX.length())));
        if (found.isEmpty()) {
            return null;
        }
        Machine machine = found.get();
        MachineProvider provider = machine.provider();
        MachineViewLookup lookup = lookup(machine);
        return new Detail(FactoryTerminalTargetKind.MACHINE, key,
                lookup.name(), provider.mode(), lookup.online(),
                provider.capacity(), provider.patternCount());
    }

    /**
     * 机器目标的稳定键。
     *
     * @param machineId 机器 id
     * @return 键
     */
    public static String keyOf(java.util.UUID machineId) {
        return MACHINE_PREFIX + machineId;
    }

    /**
     * 解析机器 id；非法字符串返回全零 uuid，查表自然查不到。
     *
     * @param raw 字符串
     * @return 机器 id
     */
    private static java.util.UUID parseMachineId(String raw) {
        try {
            return java.util.UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return new java.util.UUID(0L, 0L);
        }
    }

    /**
     * 取机器当前的显示名与在线状态；方块 id 来自观测快照，读路径不探世界。
     *
     * @param machine 机器
     * @return 名称与在线状态
     */
    private MachineViewLookup lookup(Machine machine) {
        FactoryRuntime runtime = FactoryRuntime.get();
        ObservationService observations = runtime == null ? null : runtime.observations();
        long now = this.player.serverLevel().getGameTime();
        for (FactorySnapshotBuilder.MachineView view : FactorySnapshotBuilder.machines(
                this.factory, observations, now)) {
            if (view.ref().id().equals(machine.id())) {
                return new MachineViewLookup(nameOf(view.blockId(), machine),
                        view.health() == Health.PRESENT);
            }
        }
        return new MachineViewLookup("机器 " + machine.ref().shortId(), false);
    }

    /** 机器显示名与在线状态的临时组合。 */
    private record MachineViewLookup(String name, boolean online) {
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
        Component name = block.getName();
        return name.getString();
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
        Block block = BuiltInRegistries.BLOCK.get(blockId);
        return new ItemStack(block.asItem());
    }
}
