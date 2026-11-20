package io.github.crafteve.ae2vf.item;

import io.github.crafteve.ae2vf.client.wand.MachineWandInput;

import org.jetbrains.annotations.Nullable;

import appeng.api.implementations.menuobjects.IMenuItem;
import appeng.api.implementations.menuobjects.ItemMenuHost;
import appeng.menu.locator.ItemMenuHostLocator;

import io.github.crafteve.ae2vf.menu.wand.WandDraftHost;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 机器绑定杖：1:1 toggle 与草稿编辑的入口物品。
 *
 * <p>不可堆叠、无耐久。右键手势在客户端 {@code client.wand.MachineWandInput}，服务端判定在
 * {@code command.CommandWand}。实现 {@link IMenuItem} 只是让 AE2 能按物品定位器还原草稿面板的
 * 宿主，物品类仍不引用 {@code client.*}。</p>
 *
 * <p>唯一需要物品自己做的事：在 {@link #onItemUseFirst} 里替服务端挡掉方块的
 * {@code useItemOn}。客户端 {@code PlayerInteractEvent.RightClickBlock} 的取消只终止本地预测，
 * {@code ServerboundUseItemOnPacket} 仍会发出；没有这一步，机器 GUI 会抢在手势之前打开。</p>
 */
public class MachineBindingWandItem extends Item implements IMenuItem {
    /**
     * @param properties 物品属性；由注册处统一设置不可堆叠
     */
    public MachineBindingWandItem(Properties properties) {
        super(properties);
    }

    /**
     * 右键方块：服务端抢在方块 {@code useItemOn} 之前消费掉这次交互，阻止机器 / 容器 GUI。
     *
     * <p>客户端的事件层已经把这次右键交给 {@code MachineWandInput} 并取消了预测；服务端没有
     * 事件订阅，只能靠物品在这里返回 {@link InteractionResult#CONSUME}。旧实现同样是在这个钩子
     * 里先于 GUI 执行的。</p>
     *
     * @param stack   物品
     * @param context 使用上下文
     * @return 客户端成功、服务端消费；副手交给原版
     */
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        if (context.getHand() != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        if (context.getLevel().isClientSide) {
            // 客户端手势在事件层已处理；这里只兜住本地预测，不再发请求，避免双击。
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.CONSUME;
    }

    /**
     * 给草稿面板创建宿主；AE2 在打开与反序列化两侧都会调它。
     *
     * @param player    使用物品的玩家
     * @param locator   物品定位器
     * @param hitResult 使用时的方块命中；打开面板时为 {@code null}
     * @return 面板宿主
     */
    @Override
    public ItemMenuHost<?> getMenuHost(Player player, ItemMenuHostLocator locator,
            @Nullable BlockHitResult hitResult) {
        return new WandDraftHost(this, player, locator);
    }
}
