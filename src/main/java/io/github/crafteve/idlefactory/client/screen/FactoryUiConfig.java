package io.github.crafteve.idlefactory.client.screen;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 客户端界面偏好。
 *
 * <p>排序键 / 方向 / 范围是纯客户端视图状态，写入 {@code config/idlefactory-client.toml}：
 * 关闭界面、重启客户端都会保留；不写存档、不参与服务端逻辑。</p>
 *
 * <p>配置项只在客户端读取；服务端不会注册这份配置。</p>
 */
public final class FactoryUiConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.push("sort");
    }

    /** 仓库排序键。 */
    public static final ModConfigSpec.ConfigValue<String> STORAGE_KEY = BUILDER
            .comment("仓库排序键：DEFAULT / COUNT / NAME / MOD / REGISTRY / KIND")
            .define("storageKey", "DEFAULT");
    /** 仓库是否降序。 */
    public static final ModConfigSpec.BooleanValue STORAGE_DESCENDING = BUILDER
            .comment("仓库排序是否降序")
            .define("storageDescending", true);
    /** 仓库显示范围。 */
    public static final ModConfigSpec.ConfigValue<String> STORAGE_SCOPE = BUILDER
            .comment("仓库显示范围：ALL / ITEMS / FLUIDS")
            .define("storageScope", "ALL");
    /** 机器库排序键。 */
    public static final ModConfigSpec.ConfigValue<String> MACHINE_KEY = BUILDER
            .comment("机器库排序键：DEFAULT / NAME / POSITION / STATUS / LINE / RULES / RECENT")
            .define("machineKey", "DEFAULT");
    /** 机器库是否降序。 */
    public static final ModConfigSpec.BooleanValue MACHINE_DESCENDING = BUILDER
            .comment("机器库排序是否降序")
            .define("machineDescending", true);

    /** 配置规格；由客户端入口注册。 */
    public static final ModConfigSpec SPEC;

    static {
        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    private FactoryUiConfig() {
        // 纯常量持有类
    }
}
