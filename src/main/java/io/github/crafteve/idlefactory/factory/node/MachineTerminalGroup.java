package io.github.crafteve.idlefactory.factory.node;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.machine.MachineProvider;

import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.stacks.AEItemKey;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 一台机器在 AE2 样板访问终端里的分组：图标、名字、提示行与排序键。
 *
 * <p>分组内容一变，AE2 的样板访问终端就会整屏刷新，所以这里只缓存低频变化的东西：容量、
 * 方块在不在；样板数量这类每 tick 都会动的数不进分组。名字带机器短 id，保证每台机器自成
 * 一组，不会被终端按相同分组并成一行。</p>
 */
final class MachineTerminalGroup {
    private final MinecraftServer server;
    private final Machine machine;
    private final long sortOrder;
    /** 机器方块是否在线，决定提示行。 */
    private boolean available;
    /** 机器方块的物品图标；方块不在时为 {@code null}。 */
    @Nullable
    private AEItemKey icon;
    private PatternContainerGroup group;

    /**
     * @param server    服务端实例，用来取机器所在世界
     * @param machine   机器记录
     * @param available 机器方块此刻是否在线
     */
    MachineTerminalGroup(MinecraftServer server, Machine machine, boolean available) {
        this.server = server;
        this.machine = machine;
        this.available = available;
        this.sortOrder = sortOrder(machine);
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
     * 机器方块可用性变化：图标与提示行都要跟着变。
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

    /** 重查图标并重建分组。 */
    private void refresh() {
        this.icon = this.resolveIcon();
        this.group = this.build();
    }

    /**
     * 建分组：提示行放容量，机器不在时补一行红字。
     *
     * @return 分组
     */
    private PatternContainerGroup build() {
        Component name = Component.literal("机器 " + this.machine.ref().shortId());
        MachineProvider provider = this.machine.provider();
        Component capacity = Component.literal("容量 " + provider.capacity() + " 槽");
        if (this.available) {
            return new PatternContainerGroup(this.icon, name, List.of(capacity));
        }
        return new PatternContainerGroup(this.icon, name, List.of(
                capacity,
                Component.literal("方块不在").withStyle(ChatFormatting.RED)));
    }

    /**
     * 解析机器方块的物品图标。
     *
     * @return 图标；方块不在、区块没加载或方块没有物品形态时为 {@code null}
     */
    @Nullable
    private AEItemKey resolveIcon() {
        ServerLevel level = this.server.getLevel(this.machine.ref().dimension());
        if (level == null || !level.isLoaded(this.machine.ref().pos())) {
            return null;
        }
        BlockState state = level.getBlockState(this.machine.ref().pos());
        if (state.isAir()) {
            return null;
        }
        ItemStack stack = new ItemStack(state.getBlock());
        return stack.isEmpty() ? null : AEItemKey.of(stack);
    }

    /**
     * 终端里的稳定排序键：按坐标摊成一个 long，同一台机器每次登录都排同一位置。
     *
     * @param machine 机器
     * @return 排序值
     */
    private static long sortOrder(Machine machine) {
        return ((long) machine.ref().pos().getZ() << 24)
                ^ ((long) machine.ref().pos().getX() << 8)
                ^ machine.ref().pos().getY();
    }
}
