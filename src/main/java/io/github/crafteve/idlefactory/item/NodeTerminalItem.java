package io.github.crafteve.idlefactory.item;

import org.jetbrains.annotations.Nullable;

import appeng.api.implementations.menuobjects.IMenuItem;
import appeng.api.implementations.menuobjects.ItemMenuHost;
import appeng.menu.MenuOpener;
import appeng.menu.locator.ItemMenuHostLocator;
import appeng.menu.locator.MenuLocators;

import io.github.crafteve.idlefactory.menu.node.NodeTerminalHost;
import io.github.crafteve.idlefactory.registry.IdleFactoryMenus;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 节点管理终端：右键打开节点列表界面。
 *
 * <p>与被动 IO 终端一样走 AE2 的 {@code base_terminal} 边框与 36 个玩家背包槽，内容区由
 * {@code NodeScreen} 自绘。只管节点、成员与节点级动作，不放 IO 规则。</p>
 */
public class NodeTerminalItem extends Item implements IMenuItem {
    /**
     * @param properties 物品属性；由注册处统一设置不可堆叠
     */
    public NodeTerminalItem(Properties properties) {
        super(properties);
    }

    /**
     * 右键打开节点管理界面。
     *
     * @param level  当前世界
     * @param player 使用物品的玩家
     * @param hand   使用的手
     * @return 客户端成功 / 服务端成功
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            MenuOpener.open(IdleFactoryMenus.NODE_TERMINAL.get(), serverPlayer,
                    MenuLocators.forHand(player, hand));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    /**
     * 按定位器还原菜单宿主。
     *
     * @param player  打开界面的玩家
     * @param locator 物品定位器
     * @param hit     方块命中结果；物品终端不用
     * @return 菜单宿主
     */
    @Override
    @Nullable
    public ItemMenuHost<?> getMenuHost(Player player, ItemMenuHostLocator locator,
            @Nullable BlockHitResult hit) {
        return new NodeTerminalHost(this, player, locator);
    }
}
