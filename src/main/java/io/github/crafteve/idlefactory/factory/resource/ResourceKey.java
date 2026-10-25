package io.github.crafteve.idlefactory.factory.resource;

import io.github.crafteve.idlefactory.IdleFactory;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * 一个资源的身份：类型 + 注册名。
 *
 * <p>能量没有注册名，固定用 {@link #ENERGY}（{@code idlefactory:fe}）。</p>
 *
 * <p>物品只记注册名，不带组件——这是既有语义，和 {@code Storage.snapshot} 的合并规则一致。</p>
 *
 * @param kind 类型
 * @param id   注册名；能量固定为 {@code idlefactory:fe}
 */
public record ResourceKey(ResourceKind kind, ResourceLocation id) {
    /** 能量的固定 key。 */
    public static final ResourceKey ENERGY = new ResourceKey(ResourceKind.ENERGY,
            ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID, "fe"));

    /**
     * 物品的 key。
     *
     * @param stack 物品样本
     * @return key
     */
    public static ResourceKey item(ItemStack stack) {
        return new ResourceKey(ResourceKind.ITEM,
                BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    /**
     * 物品的 key。
     *
     * @param id 物品注册名
     * @return key
     */
    public static ResourceKey item(ResourceLocation id) {
        return new ResourceKey(ResourceKind.ITEM, id);
    }

    /**
     * 流体的 key。
     *
     * @param stack 流体样本
     * @return key
     */
    public static ResourceKey fluid(FluidStack stack) {
        return new ResourceKey(ResourceKind.FLUID,
                BuiltInRegistries.FLUID.getKey(stack.getFluid()));
    }

    /**
     * 流体的 key。
     *
     * @param fluid 流体
     * @return key；空流体返回 {@code null}
     */
    public static ResourceKey fluid(Fluid fluid) {
        if (fluid == Fluids.EMPTY) {
            return null;
        }
        return new ResourceKey(ResourceKind.FLUID, BuiltInRegistries.FLUID.getKey(fluid));
    }

    /**
     * 流体的 key。
     *
     * @param id 流体注册名
     * @return key
     */
    public static ResourceKey fluid(ResourceLocation id) {
        return new ResourceKey(ResourceKind.FLUID, id);
    }

    /** 给显示用的一行：{@code item:minecraft:iron_ore}。 */
    public String describe() {
        return this.kind.name().toLowerCase(java.util.Locale.ROOT) + ":" + this.id;
    }
}
