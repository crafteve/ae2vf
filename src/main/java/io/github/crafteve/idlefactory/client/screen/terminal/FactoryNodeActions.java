package io.github.crafteve.idlefactory.client.screen.terminal;

import io.github.crafteve.idlefactory.client.FactoryLocateRenderer;
import io.github.crafteve.idlefactory.client.wand.WandSession;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryNodeModel.MemberRow;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryNodeModel.NodeRow;
import io.github.crafteve.idlefactory.io.DispatchMode;
import io.github.crafteve.idlefactory.node.Multiplier;
import io.github.crafteve.idlefactory.node.MultiplierMode;
import io.github.crafteve.idlefactory.command.CommandKind;
import io.github.crafteve.idlefactory.command.CommandSource;
import io.github.crafteve.idlefactory.command.FactoryCommand;
import io.github.crafteve.idlefactory.io.BlockingMode;
import io.github.crafteve.idlefactory.network.payload.FactoryCommandC2S;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 节点终端的动作：把点击翻译成一个 C2S 包或一次纯客户端定位。
 *
 * <p>只发意图，不做本地乐观修改：服务端校验后回反馈与一份新快照。倍增、逐台/轮询与编辑会话
 * 还没有后端，这里明确提示「待接入」，不伪造一个会成功的动作。</p>
 */
final class FactoryNodeActions {
    private FactoryNodeActions() {
        // 纯静态动作层
    }

    /**
     * 切换阻挡。
     *
     * @param containerId 菜单容器 id
     * @param node        节点
     * @param on          目标开关
     */
    static void setBlocking(int containerId, NodeRow node, boolean on) {
        send(containerId, node.nodeId(), CommandKind.PROVIDER_BLOCKING,
                new FactoryCommand.Blocking(on ? BlockingMode.ON : BlockingMode.OFF));
    }

    /**
     * 改节点名字。
     *
     * @param containerId 菜单容器 id
     * @param node        节点
     * @param name        新名字；空串表示恢复方块名
     */
    static void rename(int containerId, NodeRow node, String name) {
        send(containerId, node.nodeId(), CommandKind.NODE_RENAME,
                new FactoryCommand.Rename(name));
    }

    /**
     * 删除节点。
     *
     * @param containerId 菜单容器 id
     * @param node        节点
     */
    static void delete(int containerId, NodeRow node) {
        send(containerId, node.nodeId(), CommandKind.NODE_DELETE, new FactoryCommand.Empty());
    }

    /**
     * 定位成员：同维度画高亮，不同维度只报坐标。
     *
     * @param node   节点
     * @param member 成员
     */
    static void locate(NodeRow node, MemberRow member) {
        net.minecraft.core.BlockPos pos = member.machine().block().pos();
        net.minecraft.resources.ResourceLocation dimension =
                member.machine().block().dimension().location();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null
                || !minecraft.level.dimension().location().equals(dimension)) {
            message("节点在 " + dimension + " " + pos.toShortString());
            return;
        }
        FactoryLocateRenderer.show(minecraft.level.dimension(), pos);
    }

    /**
     * 进入编辑草稿：把节点 id 写进绑定杖草稿，后续蹲下右键追加机器。
     *
     * @param containerId 菜单容器 id
     * @param node        节点
     */
    static void edit(int containerId, NodeRow node) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        // 打开终端时主手是终端物品，绑定杖通常在快捷栏：先把选中槽切到它，编辑态的手势才可用。
        if (!(minecraft.player.getMainHandItem().getItem()
                instanceof io.github.crafteve.idlefactory.item.MachineBindingWandItem)) {
            int slot = wandHotbarSlot(minecraft.player);
            if (slot < 0) {
                message("把绑定杖放到快捷栏才能编辑节点");
                return;
            }
            minecraft.player.getInventory().selected = slot;
        }
        WandSession.startEdit(minecraft, node.nodeId());
    }

    /**
     * 在快捷栏里找绑定杖。
     *
     * @param player 玩家
     * @return 选中槽下标；找不到为 -1
     */
    private static int wandHotbarSlot(net.minecraft.world.entity.player.Player player) {
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().items.get(slot).getItem()
                    instanceof io.github.crafteve.idlefactory.item.MachineBindingWandItem) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * 循环倍率：关 → ×2 → ×4 → ×8 → 智能 → 关。
     *
     * @param containerId 菜单容器 id
     * @param node        节点
     */
    static void cycleMultiplier(int containerId, NodeRow node) {
        Multiplier current = node.view().core().multiplier();
        MultiplierMode mode;
        int factor;
        if (current.mode() == MultiplierMode.OFF) {
            mode = MultiplierMode.FIXED;
            factor = 2;
        } else if (current.mode() == MultiplierMode.FIXED && current.factor() < 8) {
            mode = MultiplierMode.FIXED;
            factor = current.factor() + 2;
        } else if (current.mode() == MultiplierMode.FIXED) {
            mode = MultiplierMode.SMART;
            factor = 1;
        } else {
            mode = MultiplierMode.OFF;
            factor = 1;
        }
        send(containerId, node.nodeId(), CommandKind.PROVIDER_MULTIPLIER,
                new FactoryCommand.Multiplier(mode, factor));
    }

    /**
     * 切换派单：顺序 ↔ 轮询。
     *
     * @param containerId 菜单容器 id
     * @param node        节点
     */
    static void toggleDispatch(int containerId, NodeRow node) {
        DispatchMode next = node.view().core().dispatch() == DispatchMode.ROUND_ROBIN
                ? DispatchMode.SEQUENTIAL : DispatchMode.ROUND_ROBIN;
        send(containerId, node.nodeId(), CommandKind.PROVIDER_DISPATCH,
                new FactoryCommand.Dispatch(next));
    }

    /**
     * 发一条带节点身份的玩家命令。
     *
     * @param containerId 菜单容器 id
     * @param nodeId      节点 id
     * @param kind        命令种类
     * @param body        命令数据
     */
    private static void send(int containerId, java.util.UUID nodeId, CommandKind kind,
            FactoryCommand.Body body) {
        PacketDistributor.sendToServer(new FactoryCommandC2S(new FactoryCommand(containerId, kind,
                CommandSource.PLAYER, nodeId, null, 0L, body)));
    }

    private static void message(String text) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal(text), true);
        }
    }
}