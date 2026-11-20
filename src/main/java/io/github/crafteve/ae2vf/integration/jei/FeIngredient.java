package io.github.crafteve.ae2vf.integration.jei;

import com.mojang.serialization.Codec;

import io.github.crafteve.ae2vf.IdleFactory;

import mezz.jei.api.ingredients.IIngredientType;

import net.minecraft.resources.ResourceLocation;

/**
 * JEI 里的 FE 假配料。
 *
 * <p>FE 在本模组里不是物品，也没有对应的注册物品；如果直接把它映射成红石粉交给 JEI，
 * R/U 和收藏都会落到红石粉的配方上。这里给 FE 一个独立配料身份，让 JEI 的点击、
 * 收藏和配方查询都停在 FE 本身。</p>
 *
 * <p>类型 UID 固定为 {@code idlefactory:fe}，收藏落盘后即使类名调整也能读回。</p>
 */
enum FeIngredient {
    /** 唯一的 FE 配料实例。 */
    INSTANCE;

    /** 稳定 UID；收藏持久化按它查类型。 */
    static final String UID = IdleFactory.MODID + ":fe";
    /** 图标/错误信息用的注册名，和 {@code ResourceKey.ENERGY} 的 id 保持一致。 */
    static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID, "fe");
    /** 配料类型；JEI 只把它当作可点击/可收藏的 FE 标记。 */
    static final IIngredientType<FeIngredient> TYPE = new Type();
    /** 收藏持久化使用的 codec；只写一个固定字符串，避免依赖物品序列化。 */
    static final Codec<FeIngredient> CODEC =
            Codec.STRING.xmap(ignored -> INSTANCE, ignored -> "FE");

    /**
     * 配料类型实现。
     *
     * <p>单独写一个类而不是 lambda，是为了把 UID 钉死；默认 UID 是类名，重构后会变。</p>
     */
    private static final class Type implements IIngredientType<FeIngredient> {
        /**
         * 配料值的 Java 类型。
         *
         * @return {@link FeIngredient}
         */
        @Override
        public Class<? extends FeIngredient> getIngredientClass() {
            return FeIngredient.class;
        }

        /**
         * 持久化用的类型 UID。
         *
         * @return {@code idlefactory:fe}
         */
        @Override
        public String getUid() {
            return UID;
        }
    }
}
