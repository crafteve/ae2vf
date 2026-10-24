package io.github.crafteve.idlefactory.item;

import io.github.crafteve.idlefactory.menu.FactoryMenu;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 工厂终端物品。
 *
 * <p>右键时在<b>服务端</b>打开工厂工作台。客户端侧的 {@code Player#openMenu} 只会返回空
 * {@code OptionalInt}，所以界面必须由服务端广播的打开菜单包来创建。</p>
 */
public class TerminalItem extends Item {
    public TerminalItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (!level.isClientSide) {
            player.openMenu(new SimpleMenuProvider(
                    (containerId, playerInventory, p) -> new FactoryMenu(containerId, playerInventory),
                    Component.translatable("item.idlefactory.terminal")));
        }

        // 两端都返回 SUCCESS，避免客户端出现「挥手后物品没反应」的观感
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }
}
