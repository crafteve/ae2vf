package io.github.crafteve.idlefactory.client.screen;

import io.github.crafteve.idlefactory.client.screen.ResourceIconRenderer;

import io.github.crafteve.idlefactory.client.screen.FactoryLabels;

import appeng.api.stacks.AEKey;
import io.github.crafteve.idlefactory.integration.appflux.AppFluxKeys;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;
import io.github.crafteve.idlefactory.resource.AeKeyAdapter;

/**
 * 仓库格、规则图标共用的资源图标绘制。
 *
 * <p>物品走原版 {@code renderItem} + 装饰层；流体按 AE2 终端的做法取 still 贴图并染色；
 * FE 优先用 Applied Flux 的 {@code EnergyType.FE} 图标，未装附属时退回「水贴图染绿」，
 * 保证任何环境都有可辨认的图标。JEI 的 FE 配料也复用这份绘制，避免两处图标不一致。</p>
 */
public final class ResourceIconRenderer {
    /** FE 没有 Applied Flux 时的备用染色：绿色。 */
    private static final int FE_FALLBACK_TINT = 0xFF55FF55;
    /** 缺省染色（不做染色）。 */
    private static final int NO_TINT = 0;

    private ResourceIconRenderer() {
        // 纯静态工具类
    }

    /**
     * 画一个资源图标。
     *
     * @param graphics 画布
     * @param font     字体，物品装饰层需要
     * @param key      资源
     * @param x        左上角 x
     * @param y        左上角 y
     */
    public static void draw(GuiGraphics graphics, Font font, AEKey key, int x, int y) {
        if (AeKeyAdapter.isEnergy(key)) {
            if (!drawEnergy(graphics, x, y)) {
                drawItem(graphics, font, key, x, y);
            }
        } else if (AeKeyAdapter.isFluid(key)) {
            if (!drawFluid(graphics, key, x, y)) {
                drawItem(graphics, font, key, x, y);
            }
        } else {
            drawItem(graphics, font, key, x, y);
        }
    }

    private static void drawItem(GuiGraphics graphics, Font font, AEKey key, int x, int y) {
        ItemStack stack = FactoryLabels.icon(key);
        graphics.renderItem(stack, x, y);
        // 数量由调用方自己画（支持 1.2k 这类长数字），这里只借装饰层画耐久/冷却。
        graphics.renderItemDecorations(font, stack, x, y, "");
    }

    private static boolean drawFluid(GuiGraphics graphics, AEKey key, int x, int y) {
        Fluid fluid = BuiltInRegistries.FLUID.get(key.getId());
        if (fluid == Fluids.EMPTY) {
            return false;
        }
        return drawFluidSprite(graphics, fluid, x, y, NO_TINT);
    }

    private static boolean drawEnergy(GuiGraphics graphics, int x, int y) {
        ResourceLocation icon = AppFluxKeys.feIcon();
        if (icon != null && blitSprite(graphics, icon, x, y, 0xFFFFFFFF)) {
            return true;
        }
        // 没有 Applied Flux 时的 AE2 风格替代：水贴图染绿。
        return drawFluidSprite(graphics, Fluids.WATER, x, y, FE_FALLBACK_TINT);
    }

    private static boolean drawFluidSprite(GuiGraphics graphics, Fluid fluid, int x, int y,
            int tintOverride) {
        FluidStack stack = new FluidStack(fluid, 1);
        IClientFluidTypeExtensions ext = IClientFluidTypeExtensions.of(fluid);
        ResourceLocation still = ext.getStillTexture(stack);
        if (still == null) {
            return false;
        }
        int tint = tintOverride == NO_TINT ? ext.getTintColor(stack) : tintOverride;
        return blitSprite(graphics, still, x, y, tint);
    }

    private static boolean blitSprite(GuiGraphics graphics, ResourceLocation id, int x, int y,
            int argb) {
        TextureAtlasSprite sprite = Minecraft.getInstance()
                .getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(id);
        if (sprite == null || "missingno".equals(sprite.contents().name().getPath())) {
            return false;
        }
        float alpha = 1.0F;
        float red = ((argb >> 16) & 0xFF) / 255.0F;
        float green = ((argb >> 8) & 0xFF) / 255.0F;
        float blue = (argb & 0xFF) / 255.0F;
        graphics.blit(x, y, 0, 16, 16, sprite, red, green, blue, alpha);
        return true;
    }
}
