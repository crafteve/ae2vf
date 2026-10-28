package io.github.crafteve.idlefactory.item;

import io.github.crafteve.idlefactory.network.payload.FactoryWandActionC2S;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 存储绑定杖：右键方块绑定，蹲下取消，Ctrl 连锁；Shift+滚轮切换 ME / 容器模式。
 *
 * <p>不可堆叠、无耐久。模式与链接都在服务端工厂上，客户端只发动作；这里返回
 * {@code SUCCESS} 阻止容器/机器 GUI，蹲下右键空气则请求清空仓库。</p>
 */
public class StorageBindingWandItem extends Item {
    /**
     * @param properties 物品属性；由注册处统一设置不可堆叠
     */
    public StorageBindingWandItem(Properties properties) {
        super(properties);
    }

    /**
     * 右键方块：阻止方块 GUI，动作由客户端事件发送。
     *
     * @param context 使用上下文
     * @return 固定成功
     */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getHand() != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * 蹲下右键空气：清空当前仓库链接。
     *
     * @param level  当前世界
     * @param player 玩家
     * @param hand   使用的手
     * @return 蹲下时成功，否则交给后续逻辑
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (hand != InteractionHand.MAIN_HAND || !player.isShiftKeyDown()) {
            return InteractionResultHolder.pass(stack);
        }
        if (level.isClientSide) {
            PacketDistributor.sendToServer(new FactoryWandActionC2S(
                    FactoryWandActionC2S.Action.STORAGE_CLEAR, null, false));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
