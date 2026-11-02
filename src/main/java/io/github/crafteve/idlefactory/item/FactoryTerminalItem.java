package io.github.crafteve.idlefactory.item;

import org.jetbrains.annotations.Nullable;

import appeng.api.implementations.menuobjects.IMenuItem;
import appeng.api.implementations.menuobjects.ItemMenuHost;
import appeng.menu.locator.ItemMenuHostLocator;

import io.github.crafteve.idlefactory.menu.FactoryTerminalHost;
import io.github.crafteve.idlefactory.menu.FactoryTerminalMenus;
import io.github.crafteve.idlefactory.registry.ModItems;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 工厂终端：右键打开工厂界面，和 {@code /idf gui} 走同一个入口。
 *
 * <p>不可堆叠、无耐久；快捷键打开的逻辑在客户端按键与 C2S 包里，服务端会再检查一次
 * 玩家身上是否带终端。它是 AE2 的 {@link IMenuItem}，因为新终端由 AE2 的菜单承载，
 * 客户端要靠物品定位器在本地还原同一个物品作为菜单宿主。</p>
 */
public class FactoryTerminalItem extends Item implements IMenuItem {
    /**
     * @param properties 物品属性；由注册处统一设置不可堆叠
     */
    public FactoryTerminalItem(Properties properties) {
        super(properties);
    }

    /**
     * 右键打开工厂界面。
     *
     * <p>只在服务端真正打开菜单；客户端返回成功以播放手部动作。这样和 {@code /idf gui}
     * 共用 {@link FactoryTerminalMenus}，不会出现两条打开逻辑。</p>
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
            FactoryTerminalMenus.openFromHand(serverPlayer, hand);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    /**
     * 按定位器还原菜单宿主；服务端与客户端各调一次。
     *
     * <p>客户端没有工厂运行时，宿主会返回空库存与空节点，但类型必须是
     * {@link FactoryTerminalHost}，否则菜单类型声明的主机类对不上，终端会静默打不开。</p>
     *
     * @param player  打开终端的玩家
     * @param locator 物品定位器
     * @param hit     方块命中结果；物品终端不用
     * @return 菜单宿主
     */
    @Override
    @Nullable
    public ItemMenuHost<?> getMenuHost(Player player, ItemMenuHostLocator locator,
            @Nullable BlockHitResult hit) {
        return new FactoryTerminalHost(this, player, locator);
    }

    /**
     * 玩家身上（主背包或副手）是否带工厂终端。
     *
     * <p>快捷键发请求前先查一次，服务端收到请求后再查一次；两边用的是同一个方法，
     * 避免客户端显示能开、服务端却拒绝。</p>
     *
     * @param player 玩家
     * @return 带终端时为 {@code true}
     */
    public static boolean isCarriedBy(Player player) {
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(ModItems.FACTORY_TERMINAL.get())) {
                return true;
            }
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (stack.is(ModItems.FACTORY_TERMINAL.get())) {
                return true;
            }
        }
        return false;
    }
}
