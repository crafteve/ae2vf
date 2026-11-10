package io.github.crafteve.idlefactory.item;

import org.jetbrains.annotations.Nullable;

import appeng.api.implementations.menuobjects.IMenuItem;
import appeng.api.implementations.menuobjects.ItemMenuHost;
import appeng.menu.locator.ItemMenuHostLocator;

import io.github.crafteve.idlefactory.menu.wand.WandMenuHost;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 机器绑定杖：1:1 toggle 与草稿编辑的入口物品。
 *
 * <p>不可堆叠、无耐久。它自己不做任何交互判断：右键手势在客户端
 * {@code client.wand.WandGesture}，服务端判定在 {@code factory.WandService}。实现
 * {@link IMenuItem} 只是让 AE2 能按物品定位器还原草稿面板的宿主，物品类仍不引用
 * {@code client.*}。</p>
 */
public class MachineBindingWandItem extends Item implements IMenuItem {
    /**
     * @param properties 物品属性；由注册处统一设置不可堆叠
     */
    public MachineBindingWandItem(Properties properties) {
        super(properties);
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
        return new WandMenuHost(this, player, locator);
    }
}
