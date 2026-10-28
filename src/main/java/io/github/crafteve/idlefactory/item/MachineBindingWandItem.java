package io.github.crafteve.idlefactory.item;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

/**
 * 机器绑定杖：左/右键机器连接，蹲下断开，Ctrl 连锁。
 *
 * <p>不可堆叠、无耐久。实际的连接/断开在服务端 {@code FactoryService}，客户端只发动作；
 * 这里返回 {@code SUCCESS} 阻止机器打开 GUI。</p>
 */
public class MachineBindingWandItem extends Item {
    /**
     * @param properties 物品属性；由注册处统一设置不可堆叠
     */
    public MachineBindingWandItem(Properties properties) {
        super(properties);
    }

    /**
     * 右键机器：阻止机器 GUI，动作由客户端事件发送。
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
}
