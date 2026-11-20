package io.github.crafteve.ae2vf.runtime;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.ae2vf.key.BlockKey;
import io.github.crafteve.ae2vf.node.NodeCraft;
import io.github.crafteve.ae2vf.node.Node;
import io.github.crafteve.ae2vf.machine.Machine;

import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.stacks.AEItemKey;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 一个节点在 AE2 样板访问终端里的分组：图标、名字、提示行与排序键。
 *
 * <p>分组内容一变，AE2 的样板访问终端就会整屏刷新，所以这里只缓存低频变化的东西：容量、
 * 类型方块在不在；样板数量这类每 tick 都会动的数不进分组。名字优先用节点名，否则带节点短 id，
 * 保证每个节点自成一组，不会被终端按相同分组并成一行。</p>
 */
final class LiveNodePatternGroup {
    private final MinecraftServer server;
    private Node node;
    private final long sortOrder;
    /** 节点内是否至少有一台机器在线，决定提示行。 */
    private boolean available;
    /** 类型方块的物品图标；方块不在时为 {@code null}。 */
    @Nullable
    private AEItemKey icon;
    private PatternContainerGroup group;

    /**
     * @param server    服务端实例，用来取机器所在世界
     * @param node      节点记录
     * @param available 节点内是否至少有一台机器在线
     */
    LiveNodePatternGroup(MinecraftServer server, Node node, boolean available) {
        this.server = server;
        this.node = node;
        this.available = available;
        this.sortOrder = sortOrder(node);
        this.refresh();
    }

    /** 终端分组。 */
    PatternContainerGroup group() {
        return this.group;
    }

    /** 终端里的稳定排序键。 */
    long sortOrder() {
        return this.sortOrder;
    }

    /** 节点的视觉表示图标；方块不在时为 {@code null}。 */
    @Nullable
    AEItemKey icon() {
        return this.icon;
    }

    /**
     * 节点可用性变化：图标与提示行都要跟着变。
     *
     * @param next 现在是否可用
     */
    void setAvailable(boolean next) {
        if (this.available == next) {
            return;
        }
        this.available = next;
        this.refresh();
    }

    /** 样板容量变了：只重建分组，不重查方块。 */
    void refreshCapacity() {
        this.group = this.build();
    }

    /**
     * 节点改名后重建分组名；名字不落二份，直接读 {@link Node#name()}。
     */
    void refreshName() {
        this.group = this.build();
    }

    /** 换节点记录（结构变化时）并重查图标。 */
    void updateNode(Node next) {
        this.node = next;
        this.refresh();
    }

    /** 重查图标并重建分组。 */
    private void refresh() {
        this.icon = this.resolveIcon();
        this.group = this.build();
    }

    /**
     * 建分组：提示行放容量与机器数，节点离线时补一行红字。
     *
     * @return 分组
     */
    private PatternContainerGroup build() {
        Component name = this.node.name() != null
                ? Component.literal(this.node.name())
                : Component.literal("节点 " + this.node.id().toString().substring(0, 8));
        NodeCraft provider = this.node.provider();
        Component capacity = Component.literal("容量 " + provider.capacity() + " 槽");
        Component machines = Component.literal("机器 " + this.node.machineCount() + " 台");
        if (this.available) {
            return new PatternContainerGroup(this.icon, name, List.of(capacity, machines));
        }
        return new PatternContainerGroup(this.icon, name, List.of(
                capacity,
                machines,
                Component.literal("机器不在").withStyle(ChatFormatting.RED)));
    }

    /**
     * 解析类型方块的物品图标。
     *
     * @return 图标；方块不在、区块没加载或方块没有物品形态时为 {@code null}
     */
    @Nullable
    private AEItemKey resolveIcon() {
        BlockKey typeBlock = this.node.machines().get(0).block();
        ServerLevel level = this.server.getLevel(typeBlock.dimension());
        if (level == null || !level.isLoaded(typeBlock.pos())) {
            return null;
        }
        BlockState state = level.getBlockState(typeBlock.pos());
        if (state.isAir()) {
            return null;
        }
        ItemStack stack = new ItemStack(state.getBlock());
        return stack.isEmpty() ? null : AEItemKey.of(stack);
    }

    /**
     * 终端里的稳定排序键：按节点类型方块坐标摊成一个 long，同一个节点每次登录都排同一位置。
     *
     * @param node 节点
     * @return 排序值
     */
    private static long sortOrder(Node node) {
        Machine machine = node.machines().get(0);
        BlockKey typeBlock = machine.block();
        return ((long) typeBlock.pos().getZ() << 24)
                ^ ((long) typeBlock.pos().getX() << 8)
                ^ typeBlock.pos().getY();
    }
}
