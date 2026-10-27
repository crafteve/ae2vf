package io.github.crafteve.idlefactory.factory.resource;

import java.util.Locale;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * 一个资源的身份：类型 + 注册名；物品额外带一份数据组件快照。
 *
 * <p>物品的相等性与哈希直接复用 {@link ItemStack#isSameItemSameComponents} 和
 * {@link ItemStack#hashItemAndComponents}，与 AE2 的 {@code AEItemKey} 完全一致：
 * 同名不同组件（附魔、命名、药水效果、损伤）是不同资源。</p>
 *
 * <p>内部持有的 {@link ItemStack} 是构造时的副本且数量固定为 1，对外只通过
 * {@link #toStack()} 返回新的副本，因此本类不可变，可以安全地作为 Map 的键。</p>
 *
 * <p>能量没有注册名，固定用 {@link #ENERGY}（{@code idlefactory:fe}）。流体与能量不持有
 * 物品栈，仍然只按注册名识别。</p>
 */
public final class ResourceKey {
    /** 能量的固定 key。 */
    public static final ResourceKey ENERGY = new ResourceKey(ResourceKind.ENERGY,
            ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID, "fe"), null);

    private final ResourceKind kind;
    private final ResourceLocation id;
    /** 仅物品使用；找不到注册名（旧档里的未知 id）时为 {@code null}。 */
    @Nullable
    private final ItemStack stack;
    /** 构造时算好，避免在高频 Map 操作里反复拆组件。 */
    private final int hash;
    /** 是否偏离物品默认组件；构造时算好，日志/描述会频繁读。 */
    private final boolean customComponents;

    private ResourceKey(ResourceKind kind, ResourceLocation id, @Nullable ItemStack stack) {
        this.kind = kind;
        this.id = id;
        this.stack = stack;
        this.hash = stack == null
                ? Objects.hash(kind, id)
                : 31 * kind.ordinal() + ItemStack.hashItemAndComponents(stack);
        this.customComponents = stack != null
                && !ItemStack.isSameItemSameComponents(stack, new ItemStack(stack.getItem()));
    }

    /**
     * 物品的 key，带上完整组件。
     *
     * @param stack 非空物品样本；数量会被忽略
     * @return key
     * @throws IllegalArgumentException 传入空栈时
     */
    public static ResourceKey item(ItemStack stack) {
        if (stack.isEmpty()) {
            throw new IllegalArgumentException("空的 ItemStack 没有资源身份");
        }
        ItemStack copy = stack.copyWithCount(1);
        return new ResourceKey(ResourceKind.ITEM,
                BuiltInRegistries.ITEM.getKey(copy.getItem()), copy);
    }

    /**
     * 物品的 key，只给注册名（无组件）。
     *
     * <p>给旧存档和「只填 id」的规则用；注册名不存在时得到一个没有物品栈的 key，
     * {@link #toStack()} 返回空栈，诊断会把它报成「id 无法解析」。</p>
     *
     * @param id 物品注册名
     * @return key
     */
    public static ResourceKey item(ResourceLocation id) {
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item == Items.AIR) {
            return new ResourceKey(ResourceKind.ITEM, id, null);
        }
        return new ResourceKey(ResourceKind.ITEM, id, new ItemStack(item));
    }

    /**
     * 按类型和注册名构造 key。
     *
     * @param kind 类型
     * @param id   注册名
     * @return key；物品会解析成无组件栈
     */
    public static ResourceKey of(ResourceKind kind, ResourceLocation id) {
        return kind == ResourceKind.ITEM ? item(id) : new ResourceKey(kind, id, null);
    }

    /**
     * 流体的 key。
     *
     * @param stack 流体样本
     * @return key
     */
    public static ResourceKey fluid(FluidStack stack) {
        return new ResourceKey(ResourceKind.FLUID,
                BuiltInRegistries.FLUID.getKey(stack.getFluid()), null);
    }

    /**
     * 流体的 key。
     *
     * @param fluid 流体
     * @return key；空流体返回 {@code null}
     */
    @Nullable
    public static ResourceKey fluid(Fluid fluid) {
        if (fluid == Fluids.EMPTY) {
            return null;
        }
        return new ResourceKey(ResourceKind.FLUID, BuiltInRegistries.FLUID.getKey(fluid), null);
    }

    /**
     * 流体的 key。
     *
     * @param id 流体注册名
     * @return key
     */
    public static ResourceKey fluid(ResourceLocation id) {
        return new ResourceKey(ResourceKind.FLUID, id, null);
    }

    /** 类型。 */
    public ResourceKind kind() {
        return this.kind;
    }

    /** 注册名。 */
    public ResourceLocation id() {
        return this.id;
    }

    /**
     * 是否带了自定义组件。
     *
     * <p>与「物品默认状态」比较，而不是看 patch 是否非空：显式写回默认值（例如
     * {@code damage=0}）不算自定义，避免同名同效果却被拆成两种规则。</p>
     *
     * @return 物品确实偏离默认组件时为 {@code true}
     */
    public boolean hasComponents() {
        return this.customComponents;
    }

    /**
     * 取出数量为 1 的物品副本。
     *
     * @return 物品栈；非物品或注册名未知时为空栈
     */
    public ItemStack toStack() {
        return this.stack == null ? ItemStack.EMPTY : this.stack.copy();
    }

    /**
     * 取出指定数量的物品副本。
     *
     * @param count 数量，至少 1
     * @return 物品栈；非物品或注册名未知时为空栈
     */
    public ItemStack toStack(int count) {
        if (this.stack == null) {
            return ItemStack.EMPTY;
        }
        return this.stack.copyWithCount(Math.max(1, count));
    }

    /**
     * 给显示用的一行：{@code item:minecraft:iron_ore}。
     *
     * <p>带自定义组件的物品追加一个短哈希，让日志和指令能区分同名的不同变体。</p>
     *
     * @return 一行描述
     */
    public String describe() {
        if (this.kind == ResourceKind.ITEM && hasComponents()) {
            return "item:" + this.id + "#" + Integer.toHexString(this.hash);
        }
        return this.kind.name().toLowerCase(Locale.ROOT) + ":" + this.id;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ResourceKey other)) {
            return false;
        }
        if (this.kind != other.kind || !this.id.equals(other.id)) {
            return false;
        }
        if (this.kind != ResourceKind.ITEM) {
            return true;
        }
        if (this.stack == null || other.stack == null) {
            return this.stack == other.stack;
        }
        return ItemStack.isSameItemSameComponents(this.stack, other.stack);
    }

    @Override
    public int hashCode() {
        return this.hash;
    }

    @Override
    public String toString() {
        return describe();
    }
}
