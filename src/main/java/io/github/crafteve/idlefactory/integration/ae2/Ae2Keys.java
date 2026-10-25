package io.github.crafteve.idlefactory.integration.ae2;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.resource.ResourceKey;
import io.github.crafteve.idlefactory.integration.appflux.AppFluxKeys;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * {@link ResourceKey} 与 AE2 {@link AEKey} 的互转。
 *
 * <p>物品、流体是 AE2 原生；能量走 {@link AppFluxKeys}（可选模组）；化学品暂不支持。</p>
 */
public final class Ae2Keys {
    private Ae2Keys() {
        // 纯静态工具类
    }

    /**
     * 资源 → AE key。
     *
     * @param key 资源
     * @return AE key；类型不支持或注册名无效时为 {@code null}
     */
    @Nullable
    public static AEKey toAe(ResourceKey key) {
        return switch (key.kind()) {
            case ITEM -> {
                Item item = BuiltInRegistries.ITEM.get(key.id());
                yield item == Items.AIR ? null : AEItemKey.of(new ItemStack(item));
            }
            case FLUID -> {
                Fluid fluid = BuiltInRegistries.FLUID.get(key.id());
                yield fluid == Fluids.EMPTY ? null : AEFluidKey.of(new FluidStack(fluid, 1));
            }
            case ENERGY -> AppFluxKeys.feKey();
            case CHEMICAL -> null;
        };
    }

    /**
     * AE key → 资源。
     *
     * @param key AE key
     * @return 资源；不认识的 key 返回 {@code null}
     */
    @Nullable
    public static ResourceKey toResource(AEKey key) {
        if (key instanceof AEItemKey itemKey) {
            return ResourceKey.item(BuiltInRegistries.ITEM.getKey(itemKey.getItem()));
        }
        if (key instanceof AEFluidKey fluidKey) {
            return ResourceKey.fluid(BuiltInRegistries.FLUID.getKey(fluidKey.getFluid()));
        }
        if (AppFluxKeys.isFe(key)) {
            return ResourceKey.ENERGY;
        }
        return null;
    }
}
