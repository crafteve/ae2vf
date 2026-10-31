package io.github.crafteve.idlefactory.integration.ae2;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEKey;
import io.github.crafteve.idlefactory.integration.appflux.AppFluxKeys;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import io.github.crafteve.idlefactory.factory.storage.AeKeySupport;

/**
 * {@link AEKey} 与 AE2 {@link AEKey} 的互转。
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
    public static AEKey toAe(AEKey key) {
        return switch (AeKeySupport.kind(key)) {
            // 整栈往返：组件是 AEItemKey 身份的一部分，不能只传注册名。
            case ITEM -> AEItemKey.of(AeKeySupport.toStack(key));
            case FLUID -> {
                Fluid fluid = BuiltInRegistries.FLUID.get(key.getId());
                yield fluid == Fluids.EMPTY ? null : AEFluidKey.of(new FluidStack(fluid, 1));
            }
            case ENERGY -> AppFluxKeys.feKey();
        };
    }

    /**
     * AE key → 资源。
     *
     * @param key AE key
     * @return 资源；不认识的 key 返回 {@code null}
     */
    @Nullable
    public static AEKey toResource(AEKey key) {
        if (key instanceof AEItemKey itemKey) {
            return AeKeySupport.item(itemKey.getReadOnlyStack());
        }
        if (key instanceof AEFluidKey fluidKey) {
            return AeKeySupport.fluid(BuiltInRegistries.FLUID.getKey(fluidKey.getFluid()));
        }
        if (AppFluxKeys.isFe(key)) {
            return AeKeySupport.energy();
        }
        return null;
    }
}
