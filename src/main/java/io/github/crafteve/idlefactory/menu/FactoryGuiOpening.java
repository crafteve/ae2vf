package io.github.crafteve.idlefactory.menu;

import io.github.crafteve.idlefactory.menu.passiveio.PassiveIoTerminalMenu;

import io.github.crafteve.idlefactory.menu.node.NodeTerminalMenu;

import io.github.crafteve.idlefactory.menu.wand.WandMenu;

import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * 菜单归属校验。
 *
 * <p>规则动作、节点动作等负载只接受本模组注册的两个终端菜单，客户端自称的界面身份一律不认；
 * 草稿提交只接受草稿面板自己的容器 id。</p>
 */
public final class FactoryGuiOpening {
    private FactoryGuiOpening() {
        // 纯静态入口类
    }

    /**
     * 当前菜单是不是工厂自己的终端界面（被动 IO 终端或节点终端）。
     *
     * <p>容器 id 必须精确匹配，类型必须是本模组注册的两个终端菜单之一：规则动作与节点动作
     * 都靠这里拒绝过期或伪造的界面。新增工厂界面时在这里显式加类型，不要放宽成「任意菜单」。</p>
     *
     * @param menu        玩家当前菜单
     * @param containerId 负载里的容器 id
     * @return 是否允许处理该负载
     */
    public static boolean isFactoryMenu(AbstractContainerMenu menu, int containerId) {
        return menu.containerId == containerId
                && (menu instanceof PassiveIoTerminalMenu || menu instanceof NodeTerminalMenu);
    }

    /**
     * 当前菜单是不是草稿确认面板。
     *
     * @param menu        玩家当前菜单
     * @param containerId 负载里的容器 id
     * @return 是否允许处理该提交
     */
    public static boolean isWandMenu(AbstractContainerMenu menu, int containerId) {
        return menu.containerId == containerId && menu instanceof WandMenu;
    }
}
