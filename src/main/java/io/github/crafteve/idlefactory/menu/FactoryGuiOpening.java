package io.github.crafteve.idlefactory.menu;

import io.github.crafteve.idlefactory.factory.FactoryService;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * 工厂界面入口与菜单归属校验。
 *
 * <p>第二版终端删除后，这里只保留第一版工作台的兜底：{@code /idf gui} 与快捷键
 * 都打开旧工作台，不再按仓库模式分流。规则、机器表与仓库包只接受
 * {@link FactoryMenu}，客户端自称的界面身份一律不认。</p>
 */
public final class FactoryGuiOpening {
    private FactoryGuiOpening() {
        // 纯静态入口类
    }

    /**
     * 打开旧工作台；{@code /idf gui}、快捷键与终端物品都不再走第二版终端。
     *
     * @param player 玩家
     * @return 恒为 {@code true}，表示已请求打开
     */
    public static boolean openDefault(ServerPlayer player) {
        FactoryService.ensureFactory(player);
        player.openMenu(new SimpleMenuProvider(
                (containerId, playerInventory, ignored) -> new FactoryMenu(containerId,
                        playerInventory),
                Component.translatable("screen.idlefactory.title")));
        return true;
    }

    /**
     * 当前菜单是不是工厂自己的界面（旧工作台、被动 IO 终端或节点终端）。
     *
     * <p>容器 id 必须精确匹配，类型必须是本模组注册的三种菜单之一：机器表请求、规则动作、仓库
     * 请求与节点动作都靠这里拒绝过期或伪造的界面。新增工厂界面时在这里显式加类型，不要放宽成
     * 「任意菜单」。</p>
     *
     * @param menu        玩家当前菜单
     * @param containerId 负载里的容器 id
     * @return 是否允许处理该负载
     */
    public static boolean isFactoryMenu(AbstractContainerMenu menu, int containerId) {
        return menu.containerId == containerId
                && (menu instanceof FactoryMenu || menu instanceof FactoryPassiveIoMenu
                        || menu instanceof FactoryNodeMenu);
    }
}
