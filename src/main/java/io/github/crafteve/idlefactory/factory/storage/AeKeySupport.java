package io.github.crafteve.idlefactory.factory.storage;

import java.util.Locale;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.integration.appflux.AppFluxKeys;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;

/**
 * {@link AEKey} 的通用辅助：构造、模糊匹配、显示名、日志摘要与图标栈。
 *
 * <p>P2 把资源身份换成 AE2 的 {@link AEKey} 之后，「模糊 / 精确」的区别只剩「比不比数据组件」。
 * 精确直接用 {@link AEKey#equals}（{@code AEItemKey} 的相等性本来就是
 * {@code isSameItemSameComponents}）；模糊则自己写比较器，只比 {@link AEKey#getType()} 与
 * {@link AEKey#getId()}，忽略组件。这是本阶段唯一新写的算法逻辑。</p>
 *
 * <p>本类只做只读换算，不持有状态；日志与界面共用同一份摘要格式，避免两处各写一套。
 * 构造侧的 {@link #item}/{@link #fluid}/{@link #energy} 让调用方不必各自写一遍
 * 「空栈怎么办、Flux 没装怎么办」。</p>
 */
public final class AeKeySupport {
    private AeKeySupport() {
        // 纯静态工具类
    }

    /**
     * 物品的资源 key。
     *
     * @param stack 物品样本；数量会被忽略
     * @return 资源 key；空栈返回 {@code null}
     */
    @Nullable
    public static AEKey item(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return AEItemKey.of(stack.copyWithCount(1));
    }

    /**
     * 流体的资源 key。
     *
     * @param stack 流体样本
     * @return 资源 key；空流体返回 {@code null}
     */
    @Nullable
    public static AEKey fluid(FluidStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return AEFluidKey.of(stack);
    }

    /**
     * 流体的资源 key。
     *
     * @param fluid 流体
     * @return 资源 key；空流体返回 {@code null}
     */
    @Nullable
    public static AEKey fluid(Fluid fluid) {
        if (fluid == null || fluid == Fluids.EMPTY) {
            return null;
        }
        return AEFluidKey.of(new FluidStack(fluid, 1));
    }

    /**
     * 按注册名构造流体 key。
     *
     * @param id 流体注册名
     * @return 资源 key；注册名不是已知流体时返回 {@code null}
     */
    @Nullable
    public static AEKey fluid(ResourceLocation id) {
        return id == null ? null
                : fluid(net.minecraft.core.registries.BuiltInRegistries.FLUID.get(id));
    }

    /**
     * 能量（FE）的资源 key。
     *
     * <p>FE 以安装 Applied Flux 为前提：没装时没有 FE 这类资源，这里返回 {@code null}，
     * 调用方据此不提供 FE 规则，而不是造一个假的 key。</p>
     *
     * @return 资源 key；未安装 Applied Flux 时为 {@code null}
     */
    @Nullable
    public static AEKey energy() {
        return AppFluxKeys.feKey();
    }

    /**
     * 注册名。
     *
     * @param key 资源 key，可为 {@code null}
     * @return 注册名；{@code null} 时返回 {@code minecraft:air}
     */
    public static ResourceLocation id(@Nullable AEKey key) {
        return key == null
                ? ResourceLocation.fromNamespaceAndPath("minecraft", "air")
                : key.getId();
    }

    /**
     * 取出数量为 1 的物品副本。
     *
     * @param key 资源 key，可为 {@code null}
     * @return 物品栈；非物品或 {@code null} 时为空栈
     */
    public static ItemStack toStack(@Nullable AEKey key) {
        return key instanceof AEItemKey item ? item.getReadOnlyStack() : ItemStack.EMPTY;
    }

    /**
     * 模糊匹配：只比类型与注册名，忽略数据组件。
     *
     * @param left  资源 key，可为 {@code null}
     * @param right 资源 key，可为 {@code null}
     * @return 类型与注册名都相同时为 {@code true}；任一为 {@code null} 时为 {@code false}
     */
    public static boolean fuzzyEquals(@Nullable AEKey left, @Nullable AEKey right) {
        if (left == null || right == null) {
            return false;
        }
        if (left == right) {
            return true;
        }
        return left.getType().equals(right.getType()) && left.getId().equals(right.getId());
    }

    /**
     * 精确匹配：直接用 AE2 的相等性，物品带数据组件。
     *
     * @param left  资源 key，可为 {@code null}
     * @param right 资源 key，可为 {@code null}
     * @return 相等时为 {@code true}；任一为 {@code null} 时为 {@code false}
     */
    public static boolean exactEquals(@Nullable AEKey left, @Nullable AEKey right) {
        return left != null && left.equals(right);
    }

    /** 是否是物品 key。 */
    public static boolean isItem(@Nullable AEKey key) {
        return key instanceof AEItemKey;
    }

    /** 是否是流体 key。 */
    public static boolean isFluid(@Nullable AEKey key) {
        return key instanceof AEFluidKey;
    }

    /** 是否是 Applied Flux 提供的 FE key（没装附属时恒为 {@code false}）。 */
    public static boolean isEnergy(@Nullable AEKey key) {
        return key != null && AppFluxKeys.isFe(key);
    }

    /**
     * 显示名：优先用 AE2 给的本地化名字，缺失时退回注册名。
     *
     * @param key 资源 key
     * @return 显示文本
     */
    public static String displayName(@Nullable AEKey key) {
        if (key == null) {
            return "未知资源";
        }
        Component name = key.getDisplayName();
        String text = name.getString();
        return text.isEmpty() ? key.getId().toString() : text;
    }

    /**
     * 一行日志摘要：注册名 + 类型；带组件时追加内容哈希，避免同名变体混在日志里。
     *
     * <p>刻意不打印完整 {@code ItemStack} 的 NBT：太长且对定位问题没有帮助。</p>
     *
     * @param key 资源 key，可为 {@code null}
     * @return 一行摘要
     */
    public static String describe(@Nullable AEKey key) {
        if (key == null) {
            return "none";
        }
        String type = key.getType().getId().toString().toLowerCase(Locale.ROOT);
        if (key.hasComponents()) {
            return type + ":" + key.getId() + "#" + Integer.toHexString(key.hashCode());
        }
        return type + ":" + key.getId();
    }

    /**
     * 渲染用的图标栈。
     *
     * <p>物品直接给栈；流体与能量没有物品形态，用 AE2 自己给的展示栈（流体是桶，能量是附属
     * 提供的图标），这样界面上至少不是空白。</p>
     *
     * @param key 资源 key，可为 {@code null}
     * @return 图标栈；换不出来时为空栈
     */
    public static ItemStack iconStack(@Nullable AEKey key) {
        if (key == null) {
            return ItemStack.EMPTY;
        }
        ItemStack wrapped = key.wrapForDisplayOrFilter();
        return wrapped == null ? ItemStack.EMPTY : wrapped;
    }
}
