package io.github.crafteve.ae2vf.integration.jei;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

import io.github.crafteve.ae2vf.VoidFactory;
import io.github.crafteve.ae2vf.client.screen.terminal.passiveio.PassiveIoJeiIngredients;
import io.github.crafteve.ae2vf.client.screen.terminal.passiveio.PassiveIoScreen;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.registration.IGuiHandlerRegistration;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * JEI 的拖拽入口。
 *
 * <p>被动 IO 终端里的机器卡片不是原版槽位，JEI 看不到；这里注册
 * {@link IGhostIngredientHandler}，把 JEI 里的物品 / 流体拖到机器大卡片上新建规则。</p>
 *
 * <p>卡片不提供 R/U：机器行与规则行没有对应的原版槽位，JEI 的查询只覆盖玩家背包与
 * 机器容器，避免在非槽位区域伪造可点击配料。</p>
 *
 * <p>FE 不注册 JEI 配料：它走 AE2 的通用包裹堆栈（{@code GenericStack}），拖拽与查询都按
 * 包裹里的真实资源处理，不再需要 FE 假身份。</p>
 */
@JeiPlugin
public final class VoidFactoryJeiPlugin implements IModPlugin {
    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(VoidFactory.MODID, "factory_jei");
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGhostIngredientHandler(PassiveIoScreen.class,
                new PassiveIoGhostHandler());
    }

    /**
     * 把 JEI 拖过来的配料转成 AE2 的资源 key。
     *
     * <p>只接收物品与流体；FE 走 AE2 的通用包裹，会以物品形态到达，由
     * {@link #keyOfItem} 拆开。其它配料返回 {@code null}，拖拽目标为空。</p>
     *
     * @param ingredient JEI 配料
     * @return 资源 key；不支持时为 {@code null}
     */
    @Nullable
    private static AEKey toKey(Object ingredient) {
        if (ingredient instanceof ItemStack stack && !stack.isEmpty()) {
            return keyOfItem(stack);
        }
        if (ingredient instanceof FluidStack fluid && !fluid.isEmpty()) {
            return AEFluidKey.of(fluid);
        }
        return null;
    }

    /**
     * 物品配料转资源 key：先拆 AE2 的「包裹通用堆栈」，再按普通物品处理。
     *
     * <p>包裹堆栈是 AE2 用来在终端之间传非物品资源的包装纸（FE、流体、化学品都可能装在里面）。
     * 不拆的话规则会写成「搬这张纸」，机器永远收不到里面的资源。</p>
     *
     * @param stack 物品配料
     * @return 资源 key
     */
    private static AEKey keyOfItem(ItemStack stack) {
        if (GenericStack.isWrapped(stack)) {
            GenericStack inner = GenericStack.unwrapItemStack(stack);
            if (inner != null) {
                return inner.what();
            }
        }
        return AEItemKey.of(stack);
    }

    private static final class PassiveIoGhostHandler
            implements IGhostIngredientHandler<PassiveIoScreen> {
        @Override
        public <I> List<Target<I>> getTargetsTyped(PassiveIoScreen screen,
                ITypedIngredient<I> ingredient, boolean doStart) {
            AEKey key = toKey(ingredient.getIngredient());
            if (key == null) {
                return List.of();
            }
            List<Target<I>> targets = new ArrayList<>();
            for (PassiveIoJeiIngredients.AddTarget area : screen.addTargets()) {
                targets.add(new Target<>() {
                    @Override
                    public Rect2i getArea() {
                        return area.area();
                    }

                    @Override
                    public void accept(I value) {
                        screen.addFromJei(area.block(), key);
                    }
                });
            }
            return targets;
        }

        @Override
        public void onComplete() {
            if (Minecraft.getInstance().screen instanceof PassiveIoScreen screen) {
                screen.setJeiDragging(false);
            }
        }
    }
}
