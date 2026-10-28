package io.github.crafteve.idlefactory.item;

import io.github.crafteve.idlefactory.factory.FactoryService;
import io.github.crafteve.idlefactory.registry.ModItems;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 工厂终端：右键打开工厂界面，和 {@code /idf gui} 走同一个入口。
 *
 * <p>不可堆叠、无耐久；快捷键打开的逻辑在客户端按键与 C2S 包里，服务端会再检查一次
 * 玩家身上是否带终端。</p>
 */
public class FactoryTerminalItem extends Item {
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
     * 共用 {@link FactoryService#openGui(ServerPlayer)}，不会出现两条打开逻辑。</p>
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
            FactoryService.openGui(serverPlayer);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
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
