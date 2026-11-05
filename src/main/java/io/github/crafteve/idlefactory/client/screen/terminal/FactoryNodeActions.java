package io.github.crafteve.idlefactory.client.screen.terminal;

import io.github.crafteve.idlefactory.client.FactoryLocateRenderer;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryNodeModel.MemberRow;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryNodeModel.NodeRow;
import io.github.crafteve.idlefactory.network.payload.FactoryMachineActionC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryNodeProviderC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryNodeRenameC2S;

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
        PacketDistributor.sendToServer(new FactoryNodeProviderC2S(containerId, node.nodeId(),
                FactoryNodeProviderC2S.ProviderSetting.BLOCKING, on ? 1 : 0, 0));
    }

    /**
     * 改节点名字。
     *
     * @param containerId 菜单容器 id
     * @param node        节点
     * @param name        新名字；空串表示恢复方块名
     */
    static void rename(int containerId, NodeRow node, String name) {
        PacketDistributor.sendToServer(new FactoryNodeRenameC2S(containerId, node.nodeId(), name));
    }

    /**
     * 删除节点。
     *
     * @param containerId 菜单容器 id
     * @param node        节点
     */
    static void delete(int containerId, NodeRow node) {
        PacketDistributor.sendToServer(new FactoryMachineActionC2S(containerId, node.nodeId()));
    }

    /**
     * 定位成员：同维度画高亮，不同维度只报坐标。
     *
     * @param node   节点
     * @param member 成员
     */
    static void locate(NodeRow node, MemberRow member) {
        var pos = member.member().primaryPos();
        if (pos == null) {
            message("这个成员没有可用坐标");
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null
                || !minecraft.level.dimension().location().equals(member.member().dimensionId())) {
            message("节点在 " + member.member().dimensionId() + " " + pos.toShortString());
            return;
        }
        FactoryLocateRenderer.show(minecraft.level.dimension(), pos);
    }

    /** 编辑会话占位。 */
    static void editReserved() {
        message("编辑会话待接入：需要绑定杖与节点会话");
    }

    /** 倍增占位。 */
    static void multiplierReserved() {
        message("倍增待接入：样板倍率");
    }

    /** 逐台 / 轮询占位。 */
    static void dispatchReserved() {
        message("逐台 / 轮询待接入：多机派单策略");
    }

    private static void message(String text) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal(text), true);
        }
    }
}
