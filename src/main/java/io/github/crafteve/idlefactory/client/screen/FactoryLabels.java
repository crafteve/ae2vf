package io.github.crafteve.idlefactory.client.screen;

import java.util.Locale;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.client.theme.GuiTheme;
import io.github.crafteve.idlefactory.client.widget.GuiTextures;
import io.github.crafteve.idlefactory.factory.io.IoResult;
import io.github.crafteve.idlefactory.factory.resource.ResourceKey;
import io.github.crafteve.idlefactory.factory.resource.ResourceKind;
import io.github.crafteve.idlefactory.factory.resource.ResourceSpec;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
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
     * 机器方块对应的物品图标。
     *
     * @param blockId 观测到的方块 id
     * @return 图标；未知方块返回空栈
     */
    static ItemStack blockStack(@Nullable ResourceLocation blockId) {
        if (blockId == null) {
            return ItemStack.EMPTY;
        }
        Item item = BuiltInRegistries.BLOCK.get(blockId).asItem();
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    /**
     * 机器方块的显示名。
     *
     * @param blockId 观测到的方块 id
     * @return 名称；未知方块返回注册名或“未知机器”
     */
    static String blockName(@Nullable ResourceLocation blockId) {
        if (blockId == null) {
            return "未知机器";
        }
        var block = BuiltInRegistries.BLOCK.get(blockId);
        return block.asItem() == Items.AIR ? blockId.toString() : block.getName().getString();
    }

    /**
     * 资源对应的界面图标；流体按桶、能量按红石。
     *
     * @param key 资源
     * @return 图标；未知资源用屏障
     */
    static ItemStack icon(ResourceKey key) {
        return switch (key.kind()) {
            case ITEM -> key.toStack().isEmpty() ? new ItemStack(Items.BARRIER) : key.toStack();
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
            ItemStack stack = key.toStack();
            return stack.isEmpty() ? key.id().toString() : stack.getHoverName().getString();
        }
        return key.id().toString();
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
     * 资源数量在窄格子里的显示：物品/FE 压到最多 4 个字符，流体换算成 B。
     *
     * @param value 内部单位（物品个 / 流体 mB / FE）
     * @param kind  资源类型
     * @return 例如 {@code 12}、{@code 28M}、{@code 281T}、{@code 1.5B}、{@code 281kB}
     */
    static String gridCount(long value, ResourceKind kind) {
        return kind == ResourceKind.FLUID ? compactFluid(value) : compactCount(value);
    }

    private static final String[] COMPACT_UNITS = {"E", "P", "T", "G", "M", "k"};
    private static final double[] COMPACT_DIVISORS = {1.0E18, 1.0E15, 1.0E12, 1.0E9, 1.0E6, 1.0E3};

    /**
     * 仓库格专用：最多 4 个字符。
     *
     * <p>先试带 1 位小数的写法，超长就丢掉小数；例如 {@code 281.5T → 281T}。
     * 完整精度留给 tooltip 和速率。</p>
     */
    private static String compactCount(long value) {
        if (value < 0L || value < 1000L) {
            return Long.toString(value);
        }
        for (int i = 0; i < COMPACT_UNITS.length; i++) {
            double divisor = COMPACT_DIVISORS[i];
            if (value < divisor) {
                continue;
            }
            double scaled = value / divisor;
            String decimal = String.format(Locale.ROOT, "%.1f", scaled) + COMPACT_UNITS[i];
            if (decimal.length() <= 4) {
                return decimal;
            }
            String integer = Long.toString((long) scaled) + COMPACT_UNITS[i];
            if (integer.length() <= 4) {
                return integer;
            }
        }
        return Long.toString(value);
    }

    /**
     * 流体格专用：保留 B 和 0.001B 精度，最多 5 个字符；1B 以上才按 k/M/G… 缩写。
     */
    private static String compactFluid(long milliBuckets) {
        if (milliBuckets < 1000L) {
            return fluid(milliBuckets);
        }
        double buckets = milliBuckets / 1000.0D;
        for (int i = 0; i < COMPACT_UNITS.length; i++) {
            double divisor = COMPACT_DIVISORS[i];
            if (buckets < divisor) {
                continue;
            }
            double scaled = buckets / divisor;
            String decimal = String.format(Locale.ROOT, "%.1f", scaled)
                    + COMPACT_UNITS[i] + "B";
            if (decimal.length() <= 5) {
                return decimal;
            }
            String integer = Long.toString((long) scaled) + COMPACT_UNITS[i] + "B";
            if (integer.length() <= 5) {
                return integer;
            }
        }
        String fallback = fluid(milliBuckets);
        return fallback.length() <= 5 ? fallback : Long.toString((long) buckets) + "B";
    }

    /**
     * 速率显示：物品 {@code 1.2k/s}、流体 {@code 1.5B/s}、FE {@code 28.1G FE/s}。
     *
     * @param rate 内部单位每秒（物品个 / 流体 mB / FE）
     * @param kind 资源类型
     * @return 带单位的一行
     */
    static String rateText(long rate, ResourceKind kind) {
        return switch (kind) {
            case FLUID -> fluid(rate) + "/s";
            case ENERGY -> count(rate) + " FE/s";
            default -> count(rate) + "/s";
        };
    }

    /**
     * 大数量缩写：k、M、G、T、P、E，保留 1 位小数。
     *
     * <p>四舍五入后到 1000 会自动进位（例如 999950 → 1.0M），避免出现 {@code 1000.0k}。</p>
     *
     * @param count 数量
     * @return 例如 {@code 999}、{@code 1.2k}、{@code 3.4M}、{@code 28.1G}
     */
    static String count(long count) {
        if (count < 0L) {
            return Long.toString(count);
        }
        if (count < 1000L) {
            return Long.toString(count);
        }
        return scaled(count, "");
    }

    /**
     * tick 向上取整成秒，给产线睡眠提示用。
     *
     * <p>睡眠剩余按 tick 下发；显示时向上取整，避免剩余 1 tick 时出现「睡眠 0s」。</p>
     *
     * @param ticks tick 数
     * @return 秒数；非正数时为 {@code 0}
     */
    static long seconds(long ticks) {
        return ticks <= 0L ? 0L : (ticks + 19L) / 20L;
    }

    /**
     * 流体容量：内部单位是 mB，显示单位是 B。
     *
     * <p>小于 1B 时按需求逐级提高精度：先 1 位小数；若四舍五入成 {@code 0.0} 就用 2 位；
     * 仍是 {@code 0.00} 就用 3 位（即 1mB = 0.001B）。1B 以上按 k/M/G… 缩写。</p>
     *
     * @param milliBuckets 容量（mB）
     * @return 例如 {@code 0.5B}、{@code 0.04B}、{@code 0.001B}、{@code 1.5kB}
     */
    static String fluid(long milliBuckets) {
        if (milliBuckets <= 0L) {
            return "0B";
        }
        double buckets = milliBuckets / 1000.0D;
        if (buckets < 1.0D) {
            String text = String.format(Locale.ROOT, "%.1f", buckets);
            if (Double.parseDouble(text) == 0.0D) {
                text = String.format(Locale.ROOT, "%.2f", buckets);
            }
            if (Double.parseDouble(text) == 0.0D) {
                text = String.format(Locale.ROOT, "%.3f", buckets);
            }
            return text + "B";
        }
        return scaled(buckets, "B");
    }

    private static final String[] PREFIXES = {"k", "M", "G", "T", "P", "E"};

    /**
     * 把数值按 1000 进制缩写；{@code unit} 是 B 这类不能省的单位，通用数量传空串。
     */
    private static String scaled(double value, String unit) {
        int prefix = -1;
        while (value >= 1000.0D && prefix < PREFIXES.length - 1) {
            value /= 1000.0D;
            prefix++;
        }
        String text = String.format(Locale.ROOT, "%.1f", value);
        if (Double.parseDouble(text) >= 1000.0D && prefix < PREFIXES.length - 1) {
            value /= 1000.0D;
            prefix++;
            text = String.format(Locale.ROOT, "%.1f", value);
        }
        return text + (prefix < 0 ? "" : PREFIXES[prefix]) + unit;
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

    /**
     * 深色 tooltip 上的诊断颜色，用霓虹色而不是面板状态色。
     *
     * @param reason 原因
     * @param theme  主题
     * @return ARGB 颜色
     */
    static int tooltipReasonColor(IoResult.Reason reason, GuiTheme theme) {
        return switch (reason) {
            case OK -> theme.tooltip().ok();
            case NO_ITEM -> theme.tooltip().idle();
            case NO_SPACE -> theme.tooltip().warn();
            case NO_HANDLER, REJECTED -> theme.tooltip().error();
        };
    }

    /**
     * 给一段文字上色。
     *
     * <p>MC 的文字颜色只认 RGB，这里把主题里的 ARGB 去掉 alpha；tooltip 的
     * {@code Component} 会保留每个片段的样式。</p>
     *
     * @param text 文字
     * @param argb 主题色
     * @return 带颜色的文本组件
     */
    static Component colored(String text, int argb) {
        return Component.literal(text)
                .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(argb & 0xFFFFFF)));
    }

    /**
     * 给一段文字上色并加粗。
     *
     * <p>原版 tooltip 背景偏深，粗体能把亮色文字从背景里拔出来；只改变文本样式，
     * 不改原版 tooltip 的绘制管线。</p>
     *
     * @param text 文字
     * @param argb 主题色
     * @return 带颜色和粗体的文本组件
     */
    static Component coloredBold(String text, int argb) {
        return Component.literal(text)
                .withStyle(Style.EMPTY
                        .withColor(TextColor.fromRgb(argb & 0xFFFFFF))
                        .withBold(true));
    }

    /**
     * 速率在 tooltip 里的颜色：错误红、堵塞橙、有流量绿、其余灰。
     *
     * @param rate   速率
     * @param reason 最近一次原因
     * @param theme  主题
     * @return ARGB 颜色
     */
    static int rateColorTooltip(long rate, IoResult.Reason reason, GuiTheme theme) {
        return switch (reason) {
            case NO_HANDLER, REJECTED -> theme.tooltip().error();
            case NO_SPACE -> theme.tooltip().warn();
            case OK, NO_ITEM -> rate > 0L ? theme.tooltip().ok() : theme.tooltip().idle();
        };
    }

    /**
     * 速率在普通界面里的颜色，和 tooltip 语义一致但用面板状态色。
     *
     * @param rate   速率
     * @param reason 最近一次原因
     * @param theme  主题
     * @return ARGB 颜色
     */
    static int rateColorUi(long rate, IoResult.Reason reason, GuiTheme theme) {
        return switch (reason) {
            case NO_HANDLER, REJECTED -> theme.error();
            case NO_SPACE -> theme.statusWarn();
            case OK, NO_ITEM -> rate > 0L ? theme.statusRun() : theme.statusIdle();
        };
    }

    /**
     * 「标签 + 值」的一行 tooltip：标签浅灰粗体、值亮色粗体。
     *
     * @param label 标签
     * @param value 值
     * @param theme 主题
     * @return 文本组件
     */
    static Component labelValue(String label, String value, GuiTheme theme) {
        return Component.empty()
                .append(colored(label, theme.tooltip().label()))
                .append(coloredBold(value, theme.tooltip().value()));
    }

    /**
     * 「面：xxx」的 tooltip 片段，面用紫色区别于数值青。
     *
     * @param label 标签
     * @param face  中文面名
     * @param faceEn 英文面名
     * @param theme 主题
     * @return 文本组件
     */
    static Component faceLine(String label, String face, String faceEn, GuiTheme theme) {
        return Component.empty()
                .append(colored(label, theme.tooltip().label()))
                .append(coloredBold(face + "（" + faceEn + "）", theme.tooltip().face()));
    }

    /**
     * 「数量 + 单位」的速率片段，数值颜色跟随流量与原因。
     *
     * @param rate   速率值
     * @param kind   资源类型
     * @param reason 最近一次原因
     * @param theme  主题
     * @return 文本组件
     */
    static Component rate(long rate, ResourceKind kind, IoResult.Reason reason,
            GuiTheme theme) {
        int color = rateColorTooltip(rate, reason, theme);
        return coloredBold(rateText(rate, kind), color);
    }

    /**
     * 「状态：xxx」的 tooltip 片段，状态本身跟随原因色并加粗。
     *
     * @param reason 原因
     * @param input  是否输入侧
     * @param theme  主题
     * @return 文本组件
     */
    static Component state(IoResult.Reason reason, boolean input, GuiTheme theme) {
        return Component.empty()
                .append(colored("状态：", theme.tooltip().label()))
                .append(coloredBold(reason(reason, input), tooltipReasonColor(reason, theme)));
    }
}
