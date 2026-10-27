package io.github.crafteve.idlefactory.client.screen;

import java.util.Locale;

import io.github.crafteve.idlefactory.client.theme.GuiTheme;
import io.github.crafteve.idlefactory.client.widget.GuiTextures;
import io.github.crafteve.idlefactory.factory.io.IoResult;
import io.github.crafteve.idlefactory.factory.resource.ResourceKey;
import io.github.crafteve.idlefactory.factory.resource.ResourceKind;
import io.github.crafteve.idlefactory.factory.resource.ResourceSpec;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 左表与 IO 详情共用的文案/图标解析。
 *
 * <p>资源名、单位、面名、数量缩写和诊断文案原来散在 {@link FactoryScreen} 里；展开详情
 * 也要用同一套说法，所以集中到这里，避免两处各写一份、翻译后对不上。</p>
 */
final class FactoryLabels {
    private FactoryLabels() {
        // 纯静态工具类
    }

    /**
     * 资源对应的界面图标；流体按桶、能量按红石。
     *
     * @param key 资源
     * @return 图标；未知资源用屏障
     */
    static ItemStack icon(ResourceKey key) {
        return switch (key.kind()) {
            case ITEM -> {
                Item item = BuiltInRegistries.ITEM.get(key.id());
                yield item == Items.AIR ? new ItemStack(Items.BARRIER) : new ItemStack(item);
            }
            case FLUID -> {
                var fluid = BuiltInRegistries.FLUID.get(key.id());
                Item bucket = fluid.getBucket();
                yield new ItemStack(bucket == Items.AIR ? Items.BUCKET : bucket);
            }
            case ENERGY -> new ItemStack(Items.REDSTONE);
            default -> new ItemStack(Items.BARRIER);
        };
    }

    /**
     * 资源显示名。
     *
     * @param key 资源
     * @return 名称；查不到时退回注册名
     */
    static String name(ResourceKey key) {
        if (key.kind() == ResourceKind.ENERGY) {
            return "FE";
        }
        if (key.kind() == ResourceKind.FLUID) {
            var fluid = BuiltInRegistries.FLUID.get(key.id());
            Item bucket = fluid.getBucket();
            return bucket == Items.AIR ? key.id().toString()
                    : new ItemStack(bucket).getHoverName().getString();
        }
        if (key.kind() == ResourceKind.ITEM) {
            Item item = BuiltInRegistries.ITEM.get(key.id());
            return item == Items.AIR ? key.id().toString()
                    : new ItemStack(item).getHoverName().getString();
        }
        return key.id().toString();
    }

    /**
     * 资源数量单位。
     *
     * @param kind 资源类型
     * @return 单位后缀
     */
    static String unit(ResourceKind kind) {
        return switch (kind) {
            case ITEM -> "个";
            case FLUID -> "mB";
            case ENERGY -> "FE";
            case CHEMICAL -> "";
        };
    }

    /**
     * 速率后缀，例如 {@code /s}、{@code mB/s}、{@code FE/s}。
     *
     * @param kind 资源类型
     * @return 速率后缀
     */
    static String rateUnit(ResourceKind kind) {
        return switch (kind) {
            case FLUID -> "mB/s";
            case ENERGY -> "FE/s";
            default -> "/s";
        };
    }

    /**
     * 配置面的中文单字。
     *
     * @param spec 规则
     * @return 上/下/北/南/东/西/任意
     */
    static String face(ResourceSpec spec) {
        if (spec.face() == null) {
            return "任意";
        }
        return switch (spec.face()) {
            case UP -> "上";
            case DOWN -> "下";
            case NORTH -> "北";
            case SOUTH -> "南";
            case EAST -> "东";
            case WEST -> "西";
        };
    }

    /**
     * 配置面的英文名，给 tooltip 和日志用。
     *
     * @param spec 规则
     * @return 方向名；不限面时为 {@code any}
     */
    static String faceEn(ResourceSpec spec) {
        return spec.face() == null ? ResourceSpec.ANY : spec.face().getName();
    }

    /**
     * 大数量缩写。
     *
     * @param count 数量
     * @return 例如 {@code 999}、{@code 1.2k}、{@code 3.4M}
     */
    static String count(long count) {
        if (count < 1000L) {
            return Long.toString(count);
        }
        if (count < 1_000_000L) {
            return String.format(Locale.ROOT, "%.1fk", count / 1000.0D);
        }
        return String.format(Locale.ROOT, "%.1fM", count / 1_000_000.0D);
    }

    /**
     * 诊断文案。
     *
     * <p>输入和输出对 {@code NO_ITEM}/{@code NO_SPACE} 的解释相反：输入侧没料是仓库缺货，
     * 输出侧没料是机器没产出；输入侧没空间是机器满，输出侧没空间是仓库满。</p>
     *
     * @param reason 原因
     * @param input  是否输入侧
     * @return 中文诊断
     */
    static String reason(IoResult.Reason reason, boolean input) {
        return switch (reason) {
            case OK -> "正常";
            case NO_ITEM -> input ? "仓库缺料" : "机器无产出";
            case NO_SPACE -> input ? "机器已满" : "仓库已满";
            case NO_HANDLER -> "无可用面";
            case REJECTED -> "该面拒收";
        };
    }

    /**
     * 诊断对应的 1px 状态条贴图。
     *
     * @param reason 原因
     * @return 状态条贴图
     */
    static ResourceLocation reasonStrip(IoResult.Reason reason) {
        return switch (reason) {
            case OK -> GuiTextures.STATUS_STRIP_RUN;
            case NO_ITEM -> GuiTextures.STATUS_STRIP_IDLE;
            case NO_SPACE -> GuiTextures.STATUS_STRIP_WARN;
            case NO_HANDLER, REJECTED -> GuiTextures.STATUS_STRIP_ERROR;
        };
    }

    /**
     * 诊断文字颜色。
     *
     * @param reason 原因
     * @param theme  主题
     * @return ARGB 颜色
     */
    static int reasonColor(IoResult.Reason reason, GuiTheme theme) {
        return switch (reason) {
            case OK -> theme.statusRun();
            case NO_ITEM -> theme.statusIdle();
            case NO_SPACE -> theme.statusWarn();
            case NO_HANDLER, REJECTED -> theme.error();
        };
    }
}
