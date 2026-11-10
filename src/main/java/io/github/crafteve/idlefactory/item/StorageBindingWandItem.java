package io.github.crafteve.idlefactory.item;

import io.github.crafteve.idlefactory.network.payload.FactoryWandActionC2S;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 存储绑定杖：右键方块 toggle，Ctrl 连锁 toggle；Shift+滚轮切换模式。
 *
 * <p>不可堆叠、无耐久。模式与链接都在服务端工厂上，客户端只发动作；右键在
 * {@link #onItemUseFirst} 里先于容器 GUI 执行。</p>
 *
 * <p>右键空气清空仓库的入口随 /idf 指令一起删除：那条链路要靠聊天栏里的
 * {@code /idf storage clear confirm} 才兑现，指令没了就只剩一个发出去也没有下文的请求。</p>
 */
public class StorageBindingWandItem extends Item {
    /**
     * @param properties 物品属性；由注册处统一设置不可堆叠
     */
    public StorageBindingWandItem(Properties properties) {
        super(properties);
    }

    /**
     * 右键方块：先于方块交互发 toggle 请求，阻止容器/机器 GUI。
     *
     * @param stack   物品
     * @param context 使用上下文
     * @return 客户端成功、服务端消费
     */
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        if (context.getHand() != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        if (context.getLevel().isClientSide) {
            PacketDistributor.sendToServer(new FactoryWandActionC2S(
                    FactoryWandActionC2S.Action.STORAGE_TOGGLE,
                    context.getClickedPos(), Screen.hasControlDown()));
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.CONSUME;
    }
}
