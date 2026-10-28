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
 * 机器绑定杖：左/右键机器连接，蹲下断开，Ctrl 连锁。
 *
 * <p>不可堆叠、无耐久。右键在 {@link #onItemUseFirst} 里先于方块 GUI 执行：客户端发包，
 * 服务端返回 {@code CONSUME}，机器没有机会打开界面。实际的连接/断开在服务端
 * {@code FactoryService}。</p>
 */
public class MachineBindingWandItem extends Item {
    /**
     * @param properties 物品属性；由注册处统一设置不可堆叠
     */
    public MachineBindingWandItem(Properties properties) {
        super(properties);
    }

    /**
     * 右键机器：先于方块交互发请求，阻止机器 GUI。
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
            FactoryWandActionC2S.Action action = Screen.hasShiftDown()
                    ? FactoryWandActionC2S.Action.MACHINE_DISCONNECT
                    : FactoryWandActionC2S.Action.MACHINE_CONNECT;
            PacketDistributor.sendToServer(new FactoryWandActionC2S(
                    action, context.getClickedPos(), Screen.hasControlDown()));
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.CONSUME;
    }
}
