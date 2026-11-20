package io.github.crafteve.idlefactory.client.screen;

import java.util.Locale;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.io.IoReason;
import appeng.api.stacks.AEKey;
import io.github.crafteve.idlefactory.machine.ResourceSpec;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import io.github.crafteve.idlefactory.resource.AeKeyAdapter;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;

/**
 * 左表与 IO 详情共用的文案/图标解析。
 *
 * <p>资源名、单位、面名、数量缩写集中在这里；被动 IO 终端的列表与展开详情都用同一套说法，
 * 避免两处各写一份、翻译后对不上。</p>
 */
public final class FactoryLabels {
    private FactoryLabels() {
        // 纯静态工具类
    }

    /**
     * 机器方块对应的物品图标。
     *
     * @param blockId 观测到的方块 id
     * @return 图标；未知方块返回空栈
     */
    public static ItemStack blockStack(@Nullable ResourceLocation blockId) {
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
    public static String blockName(@Nullable ResourceLocation blockId) {
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
    public static ItemStack icon(@Nullable AEKey key) {
        if (AeKeyAdapter.isEnergy(key)) {
            return new ItemStack(Items.REDSTONE);
        }
        if (AeKeyAdapter.isFluid(key)) {
            var fluid = BuiltInRegistries.FLUID.get(key.getId());
            Item bucket = fluid.getBucket();
            return new ItemStack(bucket == Items.AIR ? Items.BUCKET : bucket);
        }
        ItemStack stack = AeKeyAdapter.toStack(key);
        return stack.isEmpty() ? new ItemStack(Items.BARRIER) : stack;
    }

    /**
     * 资源显示名。
     *
     * @param key 资源
     * @return 名称；查不到时退回注册名
     */
    public static String name(AEKey key) {
        if (AeKeyAdapter.isEnergy(key)) {
            return "FE";
        }
        if ((key instanceof AEFluidKey)) {
            var fluid = BuiltInRegistries.FLUID.get(key.getId());
            Item bucket = fluid.getBucket();
            return bucket == Items.AIR ? key.getId().toString()
                    : new ItemStack(bucket).getHoverName().getString();
        }
        if ((key instanceof AEItemKey)) {
            ItemStack stack = AeKeyAdapter.toStack(key);
            return stack.isEmpty() ? key.getId().toString() : stack.getHoverName().getString();
        }
        return key.getId().toString();
    }

    /**
     * 配置面的中文单字。
     *
     * @param spec 规则
     * @return 上/下/北/南/东/西/任意
     */
    public static String face(ResourceSpec spec) {
        return face(spec.face());
    }

    /**
     * 面的中文单字；视图表入口。
     *
     * @param face 面；{@code null} 表示任意
     * @return 上/下/北/南/东/西/任意
     */
    public static String face(@Nullable Direction face) {
        if (face == null) {
            return "任意";
        }
        return switch (face) {
            case UP -> "上";
            case DOWN -> "下";
            case NORTH -> "北";
            case SOUTH -> "南";
            case EAST -> "东";
            case WEST -> "西";
        };
    }

    private static final String[] COMPACT_UNITS = {"E", "P", "T", "G", "M", "k"};
    private static final double[] COMPACT_DIVISORS = {1.0E18, 1.0E15, 1.0E12, 1.0E9, 1.0E6, 1.0E3};

    /**
     * 速率显示：物品 {@code 1.2k/s}、流体 {@code 1.5B/s}、FE {@code 28.1G FE/s}。
     *
     * @param rate 内部单位每秒（物品个 / 流体 mB / FE）
     * @param key  资源 key；类型决定单位
     * @return 带单位的一行
     */
    public static String rateText(long rate, @Nullable AEKey key) {
        if (AeKeyAdapter.isEnergy(key)) {
            return count(rate) + " FE/s";
        }
        if (AeKeyAdapter.isFluid(key)) {
            return fluid(rate) + "/s";
        }
        return count(rate) + "/s";
    }

    /**
     * 大数量缩写：k、M、G、T、P、E，保留 1 位小数。
     *
     * <p>四舍五入后到 1000 会自动进位（例如 999950 → 1.0M），避免出现 {@code 1000.0k}。</p>
     *
     * @param count 数量
     * @return 例如 {@code 999}、{@code 1.2k}、{@code 3.4M}、{@code 28.1G}
     */
    public static String count(long count) {
        if (count < 0L) {
            return Long.toString(count);
        }
        if (count < 1000L) {
            return Long.toString(count);
        }
        return scaled(count, "");
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
     * <p>输入和输出对 {@code NO_MATERIAL}/{@code FULL} 的解释相反：输入侧没料是仓库缺货，
     * 输出侧没料是机器没产出；输入侧没空间是机器满，输出侧没空间是仓库满。</p>
     *
     * @param reason 原因
     * @param input  是否输入侧
     * @return 中文诊断
     */
    public static String reason(IoReason reason, boolean input) {
        return switch (reason) {
            case RUN -> "正常";
            case SLEEPING -> "休息中";
            case NO_MATERIAL -> input ? "仓库缺料" : "机器无产出";
            case FULL -> input ? "机器已满" : "仓库已满";
            case NO_FACE -> "无可用面";
            case BUDGET_OUT -> "本轮预算用完";
            case REJECTED -> "该面拒收";
            case FACE_CONFIG_INVALID -> "配置面不可用";
            case ERROR -> "异常";
        };
    }

    /**
     * 深色 tooltip 上的诊断颜色，用霓虹色而不是面板状态色。
     *
     * @param reason 原因
     * @param theme  主题
     * @return ARGB 颜色
     */
    static int tooltipReasonColor(IoReason reason, FactoryGuiTheme theme) {
        return switch (reason) {
            case RUN -> theme.tooltip().ok();
            case SLEEPING -> theme.tooltip().idle();
            case NO_MATERIAL -> theme.tooltip().idle();
            case FULL -> theme.tooltip().warn();
            case BUDGET_OUT -> theme.tooltip().idle();
            case NO_FACE, REJECTED, FACE_CONFIG_INVALID, ERROR -> theme.tooltip().error();
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
    static int rateColorTooltip(long rate, IoReason reason, FactoryGuiTheme theme) {
        return switch (reason) {
            case NO_FACE, REJECTED, FACE_CONFIG_INVALID, ERROR -> theme.tooltip().error();
            case FULL -> theme.tooltip().warn();
            case RUN, SLEEPING, BUDGET_OUT, NO_MATERIAL ->
                    rate > 0L ? theme.tooltip().ok() : theme.tooltip().idle();
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
    public static int rateColorUi(long rate, IoReason reason, FactoryGuiTheme theme) {
        return switch (reason) {
            case NO_FACE, REJECTED, FACE_CONFIG_INVALID, ERROR -> theme.error();
            case FULL -> theme.statusWarn();
            case RUN, SLEEPING, BUDGET_OUT, NO_MATERIAL ->
                    rate > 0L ? theme.statusRun() : theme.statusIdle();
        };
    }

    /**
     * 把「最近一轮原因」与「上一秒速率」对齐。
     *
     * <p>速率是 1 秒窗口，原因是逐轮写入；刚搬动过 132/s 的规则可能在同一帧仍带着上一轮的
     * 红色原因。只要这一秒有流量，就按正常显示，避免出现「132/s 但状态是拒收」。</p>
     *
     * @param rate   上一秒速率
     * @param reason 最近一轮原因
     * @return 有流量时为 {@link IoReason#RUN}，否则原样返回
     */
    public static IoReason effectiveReason(long rate, IoReason reason) {
        return rate > 0L ? IoReason.RUN : reason;
    }

    /**
     * 「数量 + 单位」的速率片段，数值颜色跟随流量与原因。
     *
     * @param rate   速率值
     * @param key    资源 key；类型决定单位
     * @param reason 最近一次原因
     * @param theme  主题
     * @return 文本组件
     */
    static Component rate(long rate, @Nullable AEKey key, IoReason reason,
            FactoryGuiTheme theme) {
        int color = rateColorTooltip(rate, reason, theme);
        return coloredBold(rateText(rate, key), color);
    }

    /**
     * 「状态：xxx」的 tooltip 片段，状态本身跟随原因色并加粗。
     *
     * @param reason 原因
     * @param input  是否输入侧
     * @param theme  主题
     * @return 文本组件
     */
    static Component state(IoReason reason, boolean input, FactoryGuiTheme theme) {
        return Component.empty()
                .append(colored("状态：", theme.tooltip().label()))
                .append(coloredBold(reason(reason, input), tooltipReasonColor(reason, theme)));
    }
}
