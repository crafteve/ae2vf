package io.github.crafteve.idlefactory.integration.jei;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.client.screen.FactoryScreen;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IGuiHandlerRegistration;

import net.minecraft.resources.ResourceLocation;

/**
 * JEI 插件。
 *
 * <p>只做一件事：给 {@link FactoryScreen} 注册一个 GUI handler，让自绘的存储网格也能被
 * JEI 识别（R/U 查配方、左键聚焦）。背包部分是真实 {@code Slot}，JEI 自带处理，不用管。</p>
 *
 * <p>JEI 是可选依赖：这个类只由 JEI 的插件扫描加载，没装 JEI 时不会被加载，也不会被别处引用。</p>
 */
@JeiPlugin
public class IdleFactoryJeiPlugin implements IModPlugin {
    private static final ResourceLocation UID =
            ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID, "jei");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGuiContainerHandler(FactoryScreen.class,
                new FactoryScreenJeiHandler(registration.getJeiHelpers().getIngredientManager()));
    }
}
