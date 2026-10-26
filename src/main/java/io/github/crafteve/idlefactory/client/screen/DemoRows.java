package io.github.crafteve.idlefactory.client.screen;

import java.util.List;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * P0 用的假数据。
 *
 * <p><b>临时代码</b>：这一版只为了在游戏里量尺寸和字宽，数据全部写死在客户端。P1 接上真实快照包
 * 之后整个类删掉——所以不要在这里加任何逻辑，也不要让别的地方依赖它。</p>
 *
 * <p>造 12 台机器，覆盖四种状态：就绪（青）、缺料（琥珀）、未配置/未加载（灰）、失联（红）。
 * 图标用原版物品凑，只为让行高和图标对齐的判断接近真实。</p>
 */
public final class DemoRows {
    /** 一行假数据。 */
    public record Row(String name, State state, String detail,
            List<ItemStack> inputs, List<ItemStack> outputs, String rate) {
    }

    /** 状态。 */
    public enum State {
        /** 运行 / 就绪。 */
        OK,
        /** 缺料 / 堵料。 */
        WARN,
        /** 空闲 / 未配置 / 未加载。 */
        IDLE,
        /** 失联。 */
        ERROR
    }

    private static final List<Row> ROWS = List.of(
            new Row("高炉", State.WARN, "缺料 12s",
                    List.of(new ItemStack(Items.IRON_ORE), new ItemStack(Items.COAL)),
                    List.of(new ItemStack(Items.IRON_INGOT)), "↑2.0 ↓2.0"),
            new Row("合金炉", State.OK, "就绪",
                    List.of(new ItemStack(Items.IRON_INGOT), new ItemStack(Items.COPPER_INGOT)),
                    List.of(new ItemStack(Items.BRICK)), "↑2.0 ↓1.0"),
            new Row("压板机", State.IDLE, "未配", List.of(), List.of(), "—"),
            new Row("电解槽", State.IDLE, "未载",
                    List.of(new ItemStack(Items.WATER_BUCKET)), List.of(), "—"),
            new Row("离心机", State.ERROR, "失联", List.of(), List.of(), "—"),
            new Row("粉碎机", State.OK, "就绪",
                    List.of(new ItemStack(Items.RAW_IRON)),
                    List.of(new ItemStack(Items.REDSTONE)), "↑1.0 ↓2.0"),
            new Row("熔炼炉", State.OK, "就绪",
                    List.of(new ItemStack(Items.IRON_ORE), new ItemStack(Items.COAL)),
                    List.of(new ItemStack(Items.IRON_INGOT)), "↑1.0 ↓1.0"),
            new Row("压缩机", State.WARN, "缺料",
                    List.of(new ItemStack(Items.IRON_INGOT)),
                    List.of(new ItemStack(Items.DIAMOND)), "—"),
            new Row("结晶器", State.OK, "就绪",
                    List.of(new ItemStack(Items.LAPIS_LAZULI)),
                    List.of(new ItemStack(Items.DIAMOND)), "↑0.4 ↓0.4"),
            new Row("装配机", State.OK, "就绪",
                    List.of(new ItemStack(Items.HOPPER), new ItemStack(Items.COAL)),
                    List.of(new ItemStack(Items.DISPENSER)), "↑0.8 ↓0.8"),
            new Row("熔炉", State.OK, "就绪",
                    List.of(new ItemStack(Items.SAND), new ItemStack(Items.COAL)),
                    List.of(new ItemStack(Items.GLASS)), "↑1.2 ↓1.2"),
            new Row("酿造台", State.WARN, "缺料",
                    List.of(new ItemStack(Items.NETHER_WART)),
                    List.of(new ItemStack(Items.POTION)), "—"),
            new Row("蒸馏塔", State.OK, "就绪",
                    List.of(new ItemStack(Items.WATER_BUCKET), new ItemStack(Items.COAL)),
                    List.of(new ItemStack(Items.LAPIS_LAZULI)), "↑0.6 ↓0.6"),
            new Row("压铸机", State.WARN, "缺料",
                    List.of(new ItemStack(Items.COPPER_INGOT)),
                    List.of(new ItemStack(Items.BRICK)), "—"),
            new Row("离心分离机", State.OK, "就绪",
                    List.of(new ItemStack(Items.REDSTONE), new ItemStack(Items.RAW_IRON)),
                    List.of(new ItemStack(Items.DIAMOND)), "↑0.3 ↓0.2")
    );

    private DemoRows() {
        // 纯静态数据
    }

    /**
     * 全部假行。
     *
     * @return 不可变列表
     */
    public static List<Row> rows() {
        return ROWS;
    }

    /**
     * 假的行数（用来画底部统计）。
     *
     * @return 行数
     */
    public static int size() {
        return ROWS.size();
    }
}
