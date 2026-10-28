package io.github.crafteve.idlefactory.client.screen;

import java.util.Arrays;

/**
 * 工作台的几何：一处算完，界面只读。
 *
 * <p>界面里没有「按剩余空间自动分配」的布局器——所有位置都是常量算出来的整数。原因是 GUI 必须
 * 像素对齐，flex 式的自动分配很容易算出半像素，最后表现为边框糊成两格。</p>
 *
 * <p>但「固定」不等于「不会溢出去」。三种 GUI 缩放下的可用逻辑尺寸差一倍多（1280×684 / 853×456 /
 * 640×342），所以有两处必须能收：</p>
 *
 * <ul>
 *   <li><b>两侧留白</b>：给配方查看器（JEI/EMI/REI）让位，但不能大到把面板挤没——上限是
 *       「屏幕宽减去面板最小宽」。</li>
 *   <li><b>表格列</b>：五列有最小宽度，放不下就按优先级丢列（速率 → 输入输出 → 只剩机器+状态），
 *       <b>绝不允许列被画到右区上面</b>。被丢的列宽度记为 0，绘制方跳过。</li>
 * </ul>
 *
 * <p>右区宽度固定（{@link #RIGHT_W}），左区吸收剩余宽度。这样换分辨率只让表格变宽变窄，右区的
 * 几何常量一个都不用动。</p>
 */
public final class FactoryLayout {
    /** 面板内边距。 */
    public static final int PAD = 4;
    /** 顶栏高度。 */
    public static final int HEADER_H = 22;
    /** 右区宽度（仓库/机器）。等于物品网格宽 162 + 两侧各 4 的边距，格子贴着面板边框。 */
    public static final int RIGHT_W = 170;
    /** 右区最小可用宽度。低于它就不再压缩，转而压缩左表。 */
    public static final int MIN_RIGHT_W = 170;
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

    /** 行尾展开按钮边长；也是输出列右侧要给按钮让出的宽度。 */
    public static final int EXPAND_BUTTON = 14;
    /** IO 详情面板内边距；包含九宫格边框占的 3px。 */
    public static final int IO_PANEL_PAD = 4;
    /** IO 段头高度。 */
    public static final int IO_SECTION_H = 12;
    /** 单个规则单元格高度。 */
    public static final int IO_CELL_H = 26;
    /** 配置卡高度：比只读单元格高，容纳两行面按钮和匹配按钮。 */
    public static final int IO_CARD_H = 54;
    /** 配置卡顶部信息区高度。 */
    public static final int IO_CARD_TOP_H = 22;
    /** 配置卡面按钮宽。 */
    public static final int IO_FACE_BTN_W = 16;
    /** 配置卡面按钮高。 */
    public static final int IO_FACE_BTN_H = 12;
    /** 面按钮之间的间距。 */
    public static final int IO_FACE_GAP = 1;
    /** 配置卡内边距。 */
    public static final int IO_CARD_PAD = 3;
    /** 配置卡删除按钮边长。 */
    public static final int IO_DELETE_SIZE = 12;
    /** 展开区滚轮一次滚动的像素。 */
    public static final int IO_SCROLL_STEP = 20;
    /** 单个规则单元格最大宽度。 */
    public static final int IO_CELL_W = 132;
    /** 单个规则单元格最小宽度；再窄就放不下「速率 + 面 + 诊断」。 */
    public static final int IO_CELL_MIN_W = 96;
    /** 展开区每行最多几个单元格。 */
    public static final int IO_MAX_COLS = 6;
    /** 单元格之间的间距。 */
    public static final int IO_GAP = 4;
    /** 输入段与输出段之间的间距。 */
    public static final int IO_SECTION_GAP = 6;

    /** 默认两侧留白（像素）。刚好够配方查看器的一列原料，又不会在 853 宽下把面板挤没。 */
    public static final int DEFAULT_GUTTER = 96;
    /** 面板最大宽（屏幕再大也不无限拉长）。 */
    public static final int MAX_PANEL_W = 900;
    /** 面板最大高。 */
    public static final int MAX_PANEL_H = 540;
    /** 面板最小宽。低于它说明屏幕太窄，留白优先让路。 */
    public static final int MIN_PANEL_W = 480;
    /** 面板最小高。 */
    public static final int MIN_PANEL_H = 320;

    /** 产线下拉每一行的高度。 */
    public static final int LINE_ROW_H = 16;
    /** 产线下拉底部“新建产线”行的高度。 */
    public static final int LINE_ADD_ROW_H = 18;
    /** 产线下拉的外框/内边距。 */
    public static final int LINE_MENU_PAD = 3;
    /** 产线行的二级菜单箭头宽度。 */
    public static final int LINE_CARET_W = 12;
    /** 二级菜单每一行的高度。 */
    public static final int SUBMENU_ROW_H = 16;
    /** 命名浮层宽。 */
    public static final int DIALOG_W = 240;
    /** 命名浮层高。 */
    public static final int DIALOG_H = 96;
    /** 确认浮层宽。 */
    public static final int CONFIRM_W = 240;
    /** 确认浮层高。 */
    public static final int CONFIRM_H = 84;

    /** 列下标：机器。 */
    public static final int COL_NAME = 0;
    /** 列下标：状态。 */
    public static final int COL_STATE = 1;
    /** 列下标：输入。 */
    public static final int COL_INPUT = 2;
    /** 列下标：输出。 */
    public static final int COL_OUTPUT = 3;

    /** 四列全要时的最小宽度（顺序同上）。 */
    private static final int[] MIN_ALL = { 72, 64, 72, 72 };
    /** 四列全要时的理想宽度。 */
    private static final int[] PREF_ALL = { 150, 88, 130, 130 };
    /** 四列全要时的最大宽度；超出部分按优先级分配。 */
    private static final int[] MAX_ALL = { 160, 100, 225, 225 };
    /** 只剩机器+状态时的最小宽度。 */
    private static final int[] MIN_BASIC = { 72, 64 };
    /** 只剩机器+状态时的理想宽度。 */
    private static final int[] PREF_BASIC = { 160, 100 };
    /** 只剩机器+状态时的最大宽度。 */
    private static final int[] MAX_BASIC = { 160, 100 };

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

    /** 四列的 x 坐标（按列下标，不看可见性）。 */
    private final int[] columnX = new int[4];
    /** 四列的宽度；{@code 0} 表示这一列被丢掉了。 */
    private final int[] columnW = new int[4];
    /** 当前显示的列，按显示顺序排列，元素是逻辑列下标。构造期由 {@link #layoutColumns} 填入。 */
    private int[] columnIds = new int[0];
    /** 当前显示几列。 */
    public int columnCount;

    private FactoryLayout(int screenW, int screenH) {
        int gutter = gutter(screenW);
        this.panelW = clamp(screenW - gutter * 2, MIN_PANEL_W, MAX_PANEL_W);
        this.panelH = clamp(screenH, MIN_PANEL_H, MAX_PANEL_H);
        this.panelX = (screenW - this.panelW) / 2;
        this.panelY = (screenH - this.panelH) / 2;

        this.headerX = this.panelX + PAD;
        this.headerY = this.panelY + PAD;
        this.headerW = this.panelW - PAD * 2;

        this.bodyY = this.headerY + HEADER_H + 3;
        this.bodyH = this.panelY + this.panelH - PAD - this.bodyY;

        // 右区先按理想宽度要，但至少给左表留出五列的最小宽度；实在挤不下就压右区到下限，
        // 让左表退化成三列或两列——总之两边都要能用，谁也不许画到对方头上。
        int available = this.panelW - PAD * 2;
        int rightWanted = Math.min(RIGHT_W, available - sum(MIN_ALL) - GAP);
        this.rightW = Math.max(MIN_RIGHT_W, Math.min(RIGHT_W, rightWanted));
        this.rightX = this.panelX + this.panelW - PAD - this.rightW;
        this.leftX = this.panelX + PAD;
        this.leftW = this.rightX - GAP - this.leftX;

        this.tableX = this.leftX + 4;
        this.tableW = this.leftW - 8;
        this.tableHeaderY = this.bodyY;
        this.rowTop = this.bodyY + TABLE_HEADER_H;
        this.footerY = this.bodyY + this.bodyH - FOOTER_H;
        this.rowsVisible = Math.max(1, (this.footerY - this.rowTop) / ROW_H);

        layoutColumns(this.tableW);

        // tab 条不占主体高度：它就是顶栏的右端，和面板顶边齐平。
        this.tabY = this.headerY;
        this.searchY = this.bodyY;
        this.gridX = this.rightX + (this.rightW - GRID_COLS * CELL) / 2;
        this.gridY = this.searchY + SEARCH_H + 4;

        // 从底部往上钉：快捷栏 → 背包 → 合成，物品网格吃掉剩下的高度。
        // 这样无论面板多高，玩家背包都在固定位置，只有仓库可见行数会变。
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
     * 两侧留白。
     *
     * <p>可以用 {@code -Didlefactory.gutter=NN} 覆盖（{@code ./gradlew runClient -Pgutter=NN}）。
     * 但屏幕太窄时会自动收敛到「至少留 {@link #MIN_PANEL_W} 给面板」，否则面板会被留白挤成一条。</p>
     *
     * @param screenWidth 屏幕逻辑宽
     * @return 每侧留白像素
     */
    public static int gutter(int screenWidth) {
        int wanted = Math.max(0, Integer.getInteger("idlefactory.gutter", DEFAULT_GUTTER));
        int allowed = Math.max(0, (screenWidth - MIN_PANEL_W) / 2);
        return Math.min(wanted, allowed);
    }

    /**
     * 某一列的 x。
     *
     * @param index 列下标
     * @return x 坐标
     */
    public int columnX(int index) {
        return this.columnX[index];
    }

    /**
     * 某一列的宽；{@code 0} 表示这一列在当前尺寸下被丢掉了，绘制方必须跳过。
     *
     * @param index 列下标
     * @return 宽度
     */
    public int columnW(int index) {
        return this.columnW[index];
    }

    /**
     * 当前是否还能显示某一列。
     *
     * @param index 列下标
     * @return 宽度大于 0 时为 {@code true}
     */
    public boolean hasColumn(int index) {
        return this.columnW[index] > 0;
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

    /** 展开详情面板的宽度；和行卡片一样贴着左区两侧。 */
    public int ioPanelWidth() {
        return this.leftW - 4;
    }

    /** 展开详情面板可用于摆放单元格的宽度。 */
    public int ioInnerWidth() {
        return Math.max(0, ioPanelWidth() - IO_PANEL_PAD * 2);
    }

    /**
     * 产线下拉的最大高度：从顶栏下沿到面板底边，至少留两行。
     *
     * @return 像素高度
     */
    public int lineMenuMaxHeight() {
        int top = this.headerY + HEADER_H + 2;
        return Math.max(LINE_ROW_H * 2 + LINE_ADD_ROW_H, this.panelY + this.panelH - PAD - top);
    }

    /**
     * 产线下拉的最大宽度；名字再长也不许压到右侧面板外。
     *
     * @return 像素宽度
     */
    public int lineMenuMaxWidth() {
        return Math.max(120, this.panelW - PAD * 2);
    }

    /** 命名浮层左上角 x。 */
    public int dialogX() {
        return this.panelX + (this.panelW - DIALOG_W) / 2;
    }

    /** 命名浮层左上角 y。 */
    public int dialogY() {
        return this.panelY + (this.panelH - DIALOG_H) / 2;
    }

    /** 确认浮层左上角 x。 */
    public int confirmX() {
        return this.panelX + (this.panelW - CONFIRM_W) / 2;
    }

    /** 确认浮层左上角 y。 */
    public int confirmY() {
        return this.panelY + (this.panelH - CONFIRM_H) / 2;
    }

    /**
     * 单元格宽度。
     *
     * <p>先按最小宽度决定每行几列，再把该行剩余宽度均分给单元格；这样宽屏不会只剩
     * 左半边有内容，窄屏也不会把「速率 + 面 + 诊断」挤丢。</p>
     *
     * @return 单元格宽度
     */
    public int ioCellWidth() {
        int cols = ioCellsPerRow();
        int width = (ioInnerWidth() - (cols - 1) * IO_GAP) / cols;
        return clamp(width, IO_CELL_MIN_W, IO_CELL_W);
    }

    /**
     * 展开区每行能放几个单元格。
     *
     * @return 1..{@link #IO_MAX_COLS}
     */
    public int ioCellsPerRow() {
        int cols = (ioInnerWidth() + IO_GAP) / (IO_CELL_MIN_W + IO_GAP);
        return clamp(cols, 1, IO_MAX_COLS);
    }

    /**
     * 按优先级挑一组能放下的列，并把宽度按比例分配。
     *
     * <p>四列放不下时退化成「机器 + 状态」，IO 走 tooltip。这样任何宽度下都不会有列
     * 被画到右区上面。</p>
     */
    private void layoutColumns(int tableWidth) {
        int[] ids;
        int[] min;
        int[] pref;
        int[] max;
        int[] priority;
        if (tableWidth >= sum(MIN_ALL)) {
            ids = new int[] { COL_NAME, COL_STATE, COL_INPUT, COL_OUTPUT };
            min = MIN_ALL;
            pref = PREF_ALL;
            max = MAX_ALL;
            priority = new int[] { 2, 3, 0, 1 };
        } else {
            ids = new int[] { COL_NAME, COL_STATE };
            min = MIN_BASIC;
            pref = PREF_BASIC;
            max = MAX_BASIC;
            priority = new int[] { 0, 1 };
        }

        int[] widths = distribute(tableWidth, min, pref, max, priority);
        Arrays.fill(this.columnW, 0);
        int cursor = this.tableX;
        for (int i = 0; i < ids.length; i++) {
            this.columnX[ids[i]] = cursor;
            this.columnW[ids[i]] = widths[i];
            cursor += widths[i];
        }
        this.columnIds = ids;
        this.columnCount = ids.length;
    }

    /**
     * 按当前内容重新分配列宽：内容窄就窄，内容宽才宽。
     *
     * @param desired 机器/状态/输入/输出的期望宽度
     */
    public void relayoutColumns(int[] desired) {
        if (desired == null || desired.length < 4) {
            return;
        }
        if (this.tableW < sum(MIN_ALL)) {
            layoutColumns(this.tableW);
            return;
        }
        int[] widths = new int[4];
        for (int i = 0; i < 4; i++) {
            widths[i] = clamp(desired[i], MIN_ALL[i], MAX_ALL[i]);
        }
        int total = sum(widths);
        if (total > this.tableW) {
            int excess = total - this.tableW;
            excess = shrink(widths, 0, excess, MIN_ALL[0]);
            excess = shrink(widths, 2, excess, MIN_ALL[2]);
            excess = shrink(widths, 3, excess, MIN_ALL[3]);
            excess = shrink(widths, 1, excess, MIN_ALL[1]);
            if (excess > 0) {
                layoutColumns(this.tableW);
                return;
            }
        } else {
            int extra = this.tableW - total;
            // 内容宽度只给少量余量，避免机器名又长回去
            extra = grow(widths, 2, extra, MAX_ALL[2]);
            extra = grow(widths, 3, extra, MAX_ALL[3]);
            extra = grow(widths, 0, extra, Math.min(MAX_ALL[0], widths[0] + 20));
            extra = grow(widths, 1, extra, Math.min(MAX_ALL[1], widths[1] + 16));
        }

        Arrays.fill(this.columnW, 0);
        int cursor = this.tableX;
        int[] ids = { COL_NAME, COL_STATE, COL_INPUT, COL_OUTPUT };
        for (int i = 0; i < ids.length; i++) {
            this.columnX[ids[i]] = cursor;
            this.columnW[ids[i]] = widths[i];
            cursor += widths[i];
        }
        this.columnIds = ids;
        this.columnCount = ids.length;
    }

    private static int shrink(int[] widths, int index, int excess, int min) {
        int remove = Math.min(excess, Math.max(0, widths[index] - min));
        widths[index] -= remove;
        return excess - remove;
    }

    private static int grow(int[] widths, int index, int extra, int limit) {
        int add = Math.min(extra, Math.max(0, limit - widths[index]));
        widths[index] += add;
        return extra - add;
    }

    /**
     * 第 {@code slot} 个显示列对应的逻辑列下标。
     *
     * @param slot 显示顺序（0 起）
     * @return 逻辑列下标，见 {@link #COL_NAME} 等常量
     */
    public int columnId(int slot) {
        return this.columnIds[slot];
    }

    /**
     * 按「最小 + 剩余按理想比例分配」算一组整数宽度，总和严格等于 {@code total}。
     */
    private static int[] distribute(int total, int[] min, int[] pref, int[] max,
            int[] priority) {
        int n = min.length;
        int[] result = new int[n];
        int minSum = sum(min);
        if (total <= minSum) {
            return min.clone();
        }
        int prefSum = 0;
        for (int i = 0; i < n; i++) {
            prefSum += Math.max(pref[i], min[i]);
        }
        if (total >= prefSum) {
            for (int i = 0; i < n; i++) {
                result[i] = Math.max(pref[i], min[i]);
            }
            int remaining = total - prefSum;
            // 超出理想宽度后，优先把空间给速率/IO，而不是让机器名无限变宽
            for (int column : priority) {
                if (remaining <= 0) {
                    break;
                }
                if (column < 0 || column >= n) {
                    continue;
                }
                int add = Math.min(remaining, max[column] - result[column]);
                if (add > 0) {
                    result[column] += add;
                    remaining -= add;
                }
            }
            // 所有列都到 max 后仍有残余（屏幕极宽），补给第一列
            if (remaining > 0) {
                result[0] += remaining;
            }
            return result;
        }

        int extra = total - minSum;
        int weightSum = prefSum - minSum;
        int used = 0;
        for (int i = 0; i < n; i++) {
            int weight = Math.max(pref[i], min[i]) - min[i];
            result[i] = min[i] + (weightSum <= 0 ? 0 : extra * weight / weightSum);
            used += result[i];
        }
        // 整除残差补给第一列，保证总和精确
        result[0] += total - used;
        return result;
    }

    // priority 里存的是显示列下标（width 数组下标），不是逻辑列 id。

    private static int sum(int[] values) {
        int total = 0;
        for (int value : values) {
            total += value;
        }
        return total;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
