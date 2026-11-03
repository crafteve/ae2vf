package io.github.crafteve.idlefactory.item;

import org.jetbrains.annotations.Nullable;

import appeng.api.implementations.menuobjects.IMenuItem;
import appeng.api.implementations.menuobjects.ItemMenuHost;
import appeng.menu.MenuOpener;
import appeng.menu.locator.ItemMenuHostLocator;
import appeng.menu.locator.MenuLocators;

import io.github.crafteve.idlefactory.menu.FactoryPassiveIoHost;
import io.github.crafteve.idlefactory.registry.ModMenus;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 被动 IO 管理终端：右键打开第三版 IO 配置界面。
 *
 * <p>界面走 AE2 的 {@code base_terminal} 边框与 36 个玩家背包槽，中间内容区由
 * {@code FactoryPassiveIoScreen} 自绘。物品本身不可堆叠、无耐久，菜单宿主只负责
 * 按物品定位器还原同一个物品栈。</p>
 */
public class FactoryPassiveIoTerminalItem extends Item implements IMenuItem {
    /**
     * @param properties 物品属性；由注册处统一设置不可堆叠
     */
    public FactoryPassiveIoTerminalItem(Properties properties) {
        super(properties);
    }

    /**
     * 右键打开第三版被动 IO 界面。
     *
     * <p>只在服务端真正打开菜单；客户端返回成功以播放手部动作。菜单打开失败时不回退
     * 到旧工作台，避免两套入口在同一个物品上互相抢。</p>
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
            MenuOpener.open(ModMenus.PASSIVE_IO_TERMINAL.get(), serverPlayer,
                    MenuLocators.forHand(player, hand));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    /**
     * 按定位器还原菜单宿主；服务端与客户端各调一次。
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
        return new FactoryPassiveIoHost(this, player, locator);
    }
}
