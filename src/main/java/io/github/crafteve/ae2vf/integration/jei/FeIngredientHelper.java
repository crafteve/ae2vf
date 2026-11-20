package io.github.crafteve.ae2vf.integration.jei;

import org.jetbrains.annotations.Nullable;

import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.subtypes.UidContext;

import net.minecraft.resources.ResourceLocation;

/**
 * FE 配料的 JEI 身份信息。
 *
 * <p>FE 只有一个实例，没有子类型；显示名固定为 {@code FE}，UID 与资源 key 的注册名一致，
 * 这样配方查询、收藏和错误信息都能稳定落到同一个身份上。</p>
 */
final class FeIngredientHelper implements IIngredientHelper<FeIngredient> {
    /**
     * 配料类型。
     *
     * @return {@link FeIngredient#TYPE}
     */
    @Override
    public IIngredientType<FeIngredient> getIngredientType() {
        return FeIngredient.TYPE;
    }

    /**
     * 搜索/提示用的显示名。
     *
     * @param ingredient FE 配料
     * @return {@code FE}
     */
    @Override
    public String getDisplayName(FeIngredient ingredient) {
        return "FE";
    }

    /**
     * 旧版 UID 接口。
     *
     * @param ingredient FE 配料
     * @param context    查询上下文
     * @return {@code idlefactory:fe}
     */
    @Override
    @Deprecated
    @SuppressWarnings("removal")
    public String getUniqueId(FeIngredient ingredient, UidContext context) {
        return FeIngredient.UID;
    }

    /**
     * 新版 UID；收藏与配方索引都走它。
     *
     * @param ingredient FE 配料
     * @param context    查询上下文
     * @return {@code idlefactory:fe}
     */
    @Override
    public Object getUid(FeIngredient ingredient, UidContext context) {
        return FeIngredient.UID;
    }

    /**
     * 注册名；JEI 用它显示所属模组。
     *
     * @param ingredient FE 配料
     * @return {@code idlefactory:fe}
     */
    @Override
    public ResourceLocation getResourceLocation(FeIngredient ingredient) {
        return FeIngredient.ID;
    }

    /**
     * 错误信息；FE 没有可变字段，能给出稳定身份即可。
     *
     * @param ingredient FE 配料；可能为 {@code null}
     * @return 身份字符串
     */
    @Override
    public String getErrorInfo(@Nullable FeIngredient ingredient) {
        return ingredient == null ? "null" : FeIngredient.UID;
    }

    /**
     * 复制配料；FE 是不可变单例，直接返回原实例。
     *
     * @param ingredient FE 配料
     * @return 同一个实例
     */
    @Override
    public FeIngredient copyIngredient(FeIngredient ingredient) {
        return ingredient;
    }
}
