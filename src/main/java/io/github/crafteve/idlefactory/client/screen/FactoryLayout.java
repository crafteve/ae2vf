package io.github.crafteve.idlefactory.client.screen;

/**
 * 工作台的几何：一处算完，界面只读。
 *
 * <p>界面里没有「按剩余空间自动分配」的布局器——所有位置都是常量算出来的整数。原因是 GUI 必须
 * 像素对齐，flex 式的自动分配很容易算出半像素，最后表现为边框糊成两格。</p>
 *
 * <p>唯一的输入是屏幕逻辑尺寸和两侧留白。留白给配方查看器（JEI/EMI/REI），可以用系统属性
 * {@code -Didlefactory.gutter=NN} 临时改，方便进游戏实测「留多少才不打架」。</p>
 *
 * <p>右区宽度固定（{@link #RIGHT_W}），左区吸收剩余宽度。这样以后改窗口宽度，右区的几何常量
 * 一个都不用动。</p>
 */
public final class FactoryLayout {
    /** 面板内边距。 */
    public static final int PAD = 4;
    /** 顶栏高度。 */
    public static final int HEADER_H = 22;
    /** 右区宽度（仓库/机器）。 */
    public static final int RIGHT_W = 196;
    /** 左右区之间的间隙。 */
    public static final int GAP = 4;
    /** 左区表头高度。 */
    public static final int TABLE_HEADER_H = 18;
    /** 左区行高（含 1px 分隔线）。 */
    public static final int ROW_H = 26;
    /** 左区底部统计行高度。 */
    public static final int FOOTER_H = 14;
    /** 格子边长。 */
    public static final int CELL = 18;
    /** 物品网格列数。 */
    public static final int GRID_COLS = 9;
    /** tab 条高度。 */
    public static final int TAB_H = 16;
    /** tab 数量（仓库 / 机器）。回收站是机器的属性，不占一个 tab。 */
    public static final int TAB_COUNT = 2;
    /** 搜索框高度。 */
    public static final int SEARCH_H = 14;
    /** 合成区边长（3×3）。 */
    public static final int CRAFT = 3;
    /** 合成格子的边长：比物品格子大一圈，让 3×3 占满右区宽度。 */
    public static final int CRAFT_CELL = 20;
    /** 合成结果格边长。 */
    public static final int CRAFT_RESULT = 22;
    /** 合成区里 3×3 与结果格之间的间距（留给箭头）。 */
    public static final int CRAFT_ARROW = 24;
    /** 小标题（合成 / 背包）与内容的间距。 */
    public static final int LABEL_GAP = 12;

    /** 默认两侧留白（像素）。改成 0 就是不留给配方查看器。 */
    public static final int DEFAULT_GUTTER = 48;
    /** 面板最大宽（屏幕再大也不无限拉长）。 */
    public static final int MAX_PANEL_W = 900;
    /** 面板最大高。 */
    public static final int MAX_PANEL_H = 540;
    /** 面板最小宽。 */
    public static final int MIN_PANEL_W = 480;
    /** 面板最小高。 */
    public static final int MIN_PANEL_H = 320;

    /** 左区状态列宽。 */
    private static final int COL_STATE = 104;
    /** 左区输入列宽。 */
    private static final int COL_IN = 112;
    /** 左区输出列宽。 */
    private static final int COL_OUT = 112;
    /** 左区速率列宽。 */
    private static final int COL_RATE = 93;
    /** 机器列的最小宽度。 */
    private static final int COL_NAME_MIN = 80;

    /** 面板左上角 x。 */
    public final int panelX;
    /** 面板左上角 y。 */
    public final int panelY;
    /** 面板宽。 */
    public final int panelW;
    /** 面板高。 */
    public final int panelH;

    /** 顶栏 x。 */
    public final int headerX;
    /** 顶栏 y。 */
    public final int headerY;
    /** 顶栏宽。 */
    public final int headerW;

    /** 主体区 y。 */
    public final int bodyY;
    /** 主体区高。 */
    public final int bodyH;

    /** 左区 x。 */
    public final int leftX;
    /** 左区宽。 */
    public final int leftW;
    /** 左区表格内容左边（已含内边距）。 */
    public final int tableX;
    /** 左区表格内容宽。 */
    public final int tableW;
    /** 表头 y。 */
    public final int tableHeaderY;
    /** 第一行数据的 y。 */
    public final int rowTop;
    /** 底部统计行 y。 */
    public final int footerY;
    /** 能放下几行数据（不含表头与统计行）。 */
    public final int rowsVisible;

    /** 右区 x。 */
    public final int rightX;
    /** 右区宽。 */
    public final int rightW;

    /** tab 条 y。 */
    public final int tabY;
    /** 搜索框 y。 */
    public final int searchY;
    /** 物品网格左上角。 */
    public final int gridX;
    /** 物品网格上边。 */
    public final int gridY;
    /** 物品网格行数。 */
    public final int gridRows;
    /** 合成 3×3 左上角。 */
    public final int craftX;
    /** 合成 3×3 上边。 */
    public final int craftY;
    /** 合成结果格左上角。 */
    public final int resultX;
    /** 合成结果格上边。 */
    public final int resultY;
    /** 「合成」小标题的 y。 */
    public final int craftLabelY;
    /** 玩家背包左上角。 */
    public final int invX;
    /** 玩家背包上边。 */
    public final int invY;
    /** 「背包」小标题的 y。 */
    public final int invLabelY;
    /** 快捷栏上边。 */
    public final int hotbarY;

    /** 五列的 x 坐标。 */
    private final int[] columnX;
    /** 五列的宽度。 */
    private final int[] columnW;

    private FactoryLayout(int screenW, int screenH) {
        int gutter = gutter();
        this.panelW = clamp(screenW - gutter * 2, MIN_PANEL_W, MAX_PANEL_W);
        this.panelH = clamp(screenH, MIN_PANEL_H, MAX_PANEL_H);
        this.panelX = (screenW - this.panelW) / 2;
        this.panelY = (screenH - this.panelH) / 2;

        this.headerX = this.panelX + PAD;
        this.headerY = this.panelY + PAD;
        this.headerW = this.panelW - PAD * 2;

        this.bodyY = this.headerY + HEADER_H + 3;
        this.bodyH = this.panelY + this.panelH - PAD - this.bodyY;

        this.rightX = this.panelX + this.panelW - PAD - RIGHT_W;
        this.rightW = RIGHT_W;
        this.leftX = this.panelX + PAD;
        this.leftW = this.rightX - GAP - this.leftX;

        this.tableX = this.leftX + 4;
        this.tableW = this.leftW - 8;
        this.tableHeaderY = this.bodyY;
        this.rowTop = this.bodyY + TABLE_HEADER_H;
        this.footerY = this.bodyY + this.bodyH - FOOTER_H;
        this.rowsVisible = Math.max(1, (this.footerY - this.rowTop) / ROW_H);

        int nameW = Math.max(COL_NAME_MIN, this.tableW - (COL_STATE + COL_IN + COL_OUT + COL_RATE));
        this.columnW = new int[] { nameW, COL_STATE, COL_IN, COL_OUT, COL_RATE };
        this.columnX = new int[this.columnW.length];
        int cursor = this.tableX;
        for (int i = 0; i < this.columnW.length; i++) {
            this.columnX[i] = cursor;
            cursor += this.columnW[i];
        }

        this.tabY = this.bodyY;
        this.searchY = this.tabY + TAB_H + 4;
        this.gridX = this.rightX + (this.rightW - GRID_COLS * CELL) / 2;
        this.gridY = this.searchY + SEARCH_H + 6;

        // 从底部往上钉：快捷栏 → 背包 → 合成，物品网格吃掉剩下的高度。
        // 这样右区无论多高，玩家背包都在拇指习惯的位置，只有仓库可见行数会变。
        this.hotbarY = this.bodyY + this.bodyH - 6 - CELL;
        this.invY = this.hotbarY - 3 - 3 * CELL;
        this.invX = this.gridX;
        this.invLabelY = this.invY - LABEL_GAP;

        int craftW = CRAFT * CRAFT_CELL;
        int groupW = craftW + CRAFT_ARROW + CRAFT_RESULT;
        this.craftX = this.gridX + (GRID_COLS * CELL - groupW) / 2;
        this.craftY = this.invLabelY - 4 - craftW;
        this.craftLabelY = this.craftY - LABEL_GAP;
        this.resultX = this.craftX + craftW + CRAFT_ARROW;
        this.resultY = this.craftY + (craftW - CRAFT_RESULT) / 2;

        this.gridRows = clamp((this.craftLabelY - 6 - this.gridY) / CELL, 1, 14);
    }

    /**
     * 按屏幕逻辑尺寸算一套几何。
     *
     * @param screenWidth  屏幕逻辑宽
     * @param screenHeight 屏幕逻辑高
     * @return 布局
     */
    public static FactoryLayout of(int screenWidth, int screenHeight) {
        return new FactoryLayout(screenWidth, screenHeight);
    }

    /**
     * 两侧留白。可用 {@code -Didlefactory.gutter=NN} 覆盖，进游戏实测用。
     *
     * @return 每侧留白像素
     */
    public static int gutter() {
        return Math.max(0, Integer.getInteger("idlefactory.gutter", DEFAULT_GUTTER));
    }

    /**
     * 某一列的 x。
     *
     * @param index 0=机器 1=状态 2=输入 3=输出 4=速率
     * @return x 坐标
     */
    public int columnX(int index) {
        return this.columnX[index];
    }

    /**
     * 某一列的宽。
     *
     * @param index 列下标
     * @return 宽度
     */
    public int columnW(int index) {
        return this.columnW[index];
    }

    /**
     * 第 n 行数据的 y。
     *
     * @param row 行号（0 起）
     * @return y 坐标
     */
    public int rowY(int row) {
        return this.rowTop + row * ROW_H;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
