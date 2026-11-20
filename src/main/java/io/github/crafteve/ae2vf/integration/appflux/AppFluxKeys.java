package io.github.crafteve.ae2vf.integration.appflux;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.ae2vf.IdleFactory;

import appeng.api.stacks.AEKey;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

/**
 * Applied Flux 的 FE key 适配。
 *
 * <p>AE2 原生只存物品和流体；FE 需要 Applied Flux 这类扩展。没装时
 * {@link #available()} 为 {@code false}，FE 只走方块容器后端。</p>
 *
 * <p>Applied Flux 是可选依赖，本类在未安装时也必须能安全加载：所有 Applied Flux 类型都
 * 收进 {@link Bridge}，只有 {@code available()} 为真才会碰到它。这样即使运行时没有这个
 * 模组，也不会因为类加载失败把游戏带崩。</p>
 */
public final class AppFluxKeys {
    private static final String MOD_ID = "appflux";

    /** 缓存 ModList 查询；{@code null} 表示还没查过。 */
    @Nullable
    private static Boolean loaded;
    /** 只告警一次，避免每次能量查询都刷日志。 */
    private static boolean warned;

    private AppFluxKeys() {
        // 纯静态工具类
    }

    /** Applied Flux 是否已加载。 */
    public static boolean available() {
        Boolean cached = loaded;
        if (cached == null) {
            cached = ModList.get().isLoaded(MOD_ID);
            loaded = cached;
        }
        return cached;
    }

    /**
     * FE 的 AE key。
     *
     * @return AE key；未装 Applied Flux 或 API 不兼容时为 {@code null}
     */
    @Nullable
    public static AEKey feKey() {
        if (!available()) {
            return null;
        }
        try {
            return Bridge.feKey();
        } catch (LinkageError | RuntimeException e) {
            warnOnce(e);
            return null;
        }
    }

    /**
     * 这个 AE key 是不是 FE。
     *
     * @param key AE key
     * @return 是 FE 时为 {@code true}
     */
    public static boolean isFe(AEKey key) {
        if (!available()) {
            return false;
        }
        try {
            return Bridge.isFe(key);
        } catch (LinkageError | RuntimeException e) {
            warnOnce(e);
            return false;
        }
    }

    /**
     * FE 的界面图标。
     *
     * @return block atlas 里的精灵 id；没有 Applied Flux 时为 {@code null}
     */
    @Nullable
    public static ResourceLocation feIcon() {
        if (!available()) {
            return null;
        }
        try {
            return Bridge.feIcon();
        } catch (LinkageError | RuntimeException e) {
            warnOnce(e);
            return null;
        }
    }

    private static void warnOnce(Throwable error) {
        if (warned) {
            return;
        }
        warned = true;
        IdleFactory.LOGGER.warn("IdleFactory: Applied Flux FE 桥接不可用，FE 将退回方块容器", error);
    }

    /**
     * 真正碰 Applied Flux 类的地方。
     *
     * <p>单独收成一个内部类：未安装附属时外层类可以正常加载，只有调用这里才会抛
     * {@link LinkageError}，由外层统一降级。</p>
     */
    private static final class Bridge {
        private Bridge() {
        }

        private static AEKey feKey() {
            return com.glodblock.github.appflux.common.me.key.FluxKey.of(
                    com.glodblock.github.appflux.common.me.key.type.EnergyType.FE);
        }

        private static boolean isFe(AEKey key) {
            return key instanceof com.glodblock.github.appflux.common.me.key.FluxKey flux
                    && flux.getEnergyType()
                            == com.glodblock.github.appflux.common.me.key.type.EnergyType.FE;
        }

        private static ResourceLocation feIcon() {
            return com.glodblock.github.appflux.common.me.key.type.EnergyType.FE.getIcon();
        }
    }
}
