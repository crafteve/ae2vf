package io.github.crafteve.idlefactory.item;

import io.github.crafteve.idlefactory.network.payload.FactoryWandActionC2S;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * ME 绑定杖：右键方块 toggle 仓库绑定，再点同一个方块解绑。
 *
 * <p>不可堆叠、无耐久。锚点与链接都在服务端工厂上，客户端只发动作；右键在
 * {@link #onItemUseFirst} 里先于容器 GUI 执行。</p>
 *
 * <p>绑定失败的原因由服务端用 actionbar 文案回答，客户端不预判目标方块是不是 ME 方块。</p>
 */
public class MeBindingWandItem extends Item {
    /**
     * @param properties 物品属性；由注册处统一设置不可堆叠
     */
    public MeBindingWandItem(Properties properties) {
        super(properties);
    }

    /**
     * 右键方块：先于方块交互发绑定请求，阻止容器/机器 GUI。
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
            PacketDistributor.sendToServer(new FactoryWandActionC2S(context.getClickedPos()));
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.CONSUME;
    }
}
