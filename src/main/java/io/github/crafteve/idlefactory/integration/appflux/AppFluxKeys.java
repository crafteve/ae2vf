package io.github.crafteve.idlefactory.integration.appflux;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEKey;

import net.neoforged.fml.ModList;

/**
 * Applied Flux 的 FE key 适配。
 *
 * <p>AE2 原生只存物品和流体；FE 需要 Applied Flux 这类扩展。没装时 {@link #available()} 为
 * {@code false}，FE 只走方块容器后端。</p>
 *
 * <p>当前是占位实现：等确认 Applied Flux 的公开 API 后再填 {@link #feKey()} 与 {@link #isFe}。</p>
 */
public final class AppFluxKeys {
    private static final String MOD_ID = "appflux";

    private AppFluxKeys() {
        // 纯静态工具类
    }

    /** Applied Flux 是否已加载。 */
    public static boolean available() {
        return ModList.get().isLoaded(MOD_ID);
    }

    /**
     * FE 的 AE key。
     *
     * @return AE key；未装 Applied Flux 或 API 未接入时为 {@code null}
     */
    @Nullable
    public static AEKey feKey() {
        // TODO 接入 Applied Flux 的 FE key
        return null;
    }

    /**
     * 这个 AE key 是不是 FE。
     *
     * @param key AE key
     * @return 是 FE 时为 {@code true}
     */
    public static boolean isFe(AEKey key) {
        // TODO 接入 Applied Flux 的 FE key
        return false;
    }
}
