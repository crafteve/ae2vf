package io.github.crafteve.idlefactory.client.screen.terminal;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.menu.FactoryPassiveIoView;

/**
 * 被动 IO 内容区的排版与命中：唯一的像素来源。
 *
 * <p>块从内容顶部开始顺序铺开，坐标相对内容区（不叠加视口原点与滚动 Offset），绘制、命中、
 * tooltip 和 JEI 都从这里取同一份结果。滚动只在取值时加 {@code scroll}，不重建排版。</p>
 *
 * <p>块内结构（列表式）：机器行 20 → 规则行 20×n → 虚线新增行 20；磁贴式：机器行 20 →
 * 磁贴区（上下各 3 内边距，77×34 两列，间隔 4）。块左侧 3px 状态竖线贯穿整块。</p>
 */
final class FactoryPassiveIoLayout {
    /** 规则行高。 */
    static final int ROW_H = 20;
    /** 机器行高。 */
    static final int MACHINE_ROW_H = 20;
    /** 虚线新增行高。 */
    static final int ADD_ROW_H = 20;
    /** 块间距。 */
    static final int BLOCK_GAP = 3;
    /** 块左侧状态竖线宽。 */
    static final int STATUS_W = 3;
    /** 块内左右内边距。 */
    static final int PAD = 2;
    /** 规则行左边缘方向色条宽。 */
    static final int DIR_STRIPE_W = 2;
    /** 图标凹槽边长。 */
    static final int SLOT = 18;
    /** 行内元素间距。 */
    static final int GAP = 1;
    /** 三枚 chip 的边长。 */
    static final int CHIP = 11;
    /** 速率列宽。 */
    static final int RATE_W = 38;
    /** 磁贴宽。 */
    static final int TILE_W = 77;
    /** 磁贴高。 */
    static final int TILE_H = 34;
    /** 磁贴列间隔。 */
    static final int TILE_GAP = 4;
    /** 磁贴区内边距。 */
    static final int TILE_PAD = 3;
    /** 机器行徽标宽 / 高。 */
    static final int BADGE_W = 14;
    static final int BADGE_H = 11;
    /** 磁贴第二行（chip 行）相对磁贴顶部的偏移。 */
    static final int TILE_CHIP_Y = 20;
    /** 磁贴内容左缩进：1px 高光边 + 3px 方向条 + 2px 间距。 */
    static final int TILE_INSET = 6;
    /** 磁贴里名字的 x 偏移。 */
    static final int TILE_NAME_X = 26;

    /**
     * 内容视口。
     *
     * @param x      左边缘（屏幕坐标）
     * @param y      上边缘（屏幕坐标）
     * @param width  宽
     * @param height 高
     */
    record Viewport(int x, int y, int width, int height) {
    }

    /**
     * 一块在内容坐标系里的位置。
     *
     * @param index     块下标
     * @param top       上边缘
     * @param bodyTop   块体上边缘（机器行之下）
     * @param addTop    虚线新增行上边缘（列表式）
     * @param bottom    下边缘
     * @param ruleCount 这块有几条规则
     */
    record BlockBox(int index, int top, int bodyTop, int addTop, int bottom, int ruleCount) {
    }

    /** 命中种类。 */
    enum Kind {
        /** 机器行。 */
        MACHINE,
        /** 规则行 / 磁贴（非 chip 区域）。 */
        RULE,
        /** 方向 chip。 */
        CHIP_DIR,
        /** 面 chip。 */
        CHIP_FACE,
        /** 模式 chip。 */
        CHIP_MODE,
        /** 虚线新增行 / 虚线磁贴。 */
        ADD
    }

    /**
     * 一次命中。
     *
     * @param kind  种类
     * @param block 块下标
     * @param rule  规则下标；新增行与机器行为 -1
     */
    record Hit(Kind kind, int block, int rule) {
    }

    private final List<BlockBox> boxes;
    private final Viewport viewport;
    private final FactoryPassiveIoView view;
    private final int contentHeight;

    private FactoryPassiveIoLayout(List<BlockBox> boxes, Viewport viewport,
            FactoryPassiveIoView view, int contentHeight) {
        this.boxes = boxes;
        this.viewport = viewport;
        this.view = view;
        this.contentHeight = contentHeight;
    }

    /**
     * 排版一次。
     *
     * @param blocks   模型里的块
     * @param viewport 内容视口
     * @param view     列表 / 磁贴
     * @return 排版
     */
    static FactoryPassiveIoLayout of(List<FactoryPassiveIoModel.BlockView> blocks,
            Viewport viewport, FactoryPassiveIoView view) {
        List<BlockBox> boxes = new ArrayList<>(blocks.size());
        int cursor = 0;
        for (int i = 0; i < blocks.size(); i++) {
            FactoryPassiveIoModel.BlockView block = blocks.get(i);
            int height = blockHeight(block, view);
            boxes.add(new BlockBox(i, cursor, cursor + MACHINE_ROW_H,
                    cursor + height - ADD_ROW_H, cursor + height, block.rules().size()));
            cursor += height + BLOCK_GAP;
        }
        int contentHeight = boxes.isEmpty() ? 0 : cursor - BLOCK_GAP;
        return new FactoryPassiveIoLayout(List.copyOf(boxes), viewport, view, contentHeight);
    }

    /**
     * 一块的高度。
     *
     * @param block 块
     * @param view  视图
     * @return 像素高度
     */
    static int blockHeight(FactoryPassiveIoModel.BlockView block, FactoryPassiveIoView view) {
        int rules = block.rules().size();
        if (view == FactoryPassiveIoView.LIST) {
            return MACHINE_ROW_H + rules * ROW_H + ADD_ROW_H;
        }
        int tiles = rules + 1;
        int rows = (tiles + 1) / 2;
        return MACHINE_ROW_H + TILE_PAD + rows * TILE_H + (rows - 1) * TILE_GAP + TILE_PAD;
    }

    /** 块位置列表。 */
    List<BlockBox> boxes() {
        return this.boxes;
    }

    /** 内容总高。 */
    int contentHeight() {
        return this.contentHeight;
    }

    /**
     * 最大滚动量。
     *
     * @param viewportHeight 视口高
     * @return 非负滚动上限
     */
    int maxScroll(int viewportHeight) {
        return Math.max(0, this.contentHeight - viewportHeight);
    }

    /** 视口。 */
    Viewport viewport() {
        return this.viewport;
    }

    /** 视图。 */
    FactoryPassiveIoView view() {
        return this.view;
    }

    /**
     * 命中一块内的哪个元素。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @param scroll 滚动像素
     * @return 命中；视口外或空白处为 {@code null}
     */
    @Nullable
    Hit hit(double mouseX, double mouseY, int scroll) {
        if (mouseX < this.viewport.x() || mouseX >= this.viewport.x() + this.viewport.width()) {
            return null;
        }
        if (mouseY < this.viewport.y() || mouseY >= this.viewport.y() + this.viewport.height()) {
            return null;
        }
        int localY = (int) (mouseY - this.viewport.y()) + scroll;
        int localX = (int) mouseX;
        for (BlockBox box : this.boxes) {
            if (localY < box.top() || localY >= box.bottom()) {
                continue;
            }
            if (localY < box.bodyTop()) {
                return new Hit(Kind.MACHINE, box.index(), -1);
            }
            return this.view == FactoryPassiveIoView.LIST
                    ? hitList(box, localX, localY)
                    : hitTiles(box, localX, localY);
        }
        return null;
    }

    @Nullable
    private Hit hitList(BlockBox box, int localX, int localY) {
        if (localY >= box.addTop()) {
            return new Hit(Kind.ADD, box.index(), -1);
        }
        int rule = (localY - box.bodyTop()) / ROW_H;
        int rowY = box.bodyTop() + rule * ROW_H;
        int chipY = rowY + (ROW_H - CHIP) / 2;
        if (localY >= chipY && localY < chipY + CHIP) {
            if (inside(localX, chipX(this.viewport, 0), CHIP)) {
                return new Hit(Kind.CHIP_DIR, box.index(), rule);
            }
            if (inside(localX, chipX(this.viewport, 1), CHIP)) {
                return new Hit(Kind.CHIP_FACE, box.index(), rule);
            }
            if (inside(localX, chipX(this.viewport, 2), CHIP)) {
                return new Hit(Kind.CHIP_MODE, box.index(), rule);
            }
        }
        return new Hit(Kind.RULE, box.index(), rule);
    }

    @Nullable
    private Hit hitTiles(BlockBox box, int localX, int localY) {
        int relY = localY - (box.bodyTop() + TILE_PAD);
        if (relY < 0) {
            return null;
        }
        int row = relY / (TILE_H + TILE_GAP);
        int inTileY = relY - row * (TILE_H + TILE_GAP);
        if (inTileY >= TILE_H) {
            return null;
        }
        int col = inside(localX, tileX(this.viewport, 0), TILE_W) ? 0
                : inside(localX, tileX(this.viewport, 1), TILE_W) ? 1 : -1;
        if (col < 0) {
            return null;
        }
        int tile = row * 2 + col;
        if (tile > box.ruleCount()) {
            return null;
        }
        if (tile == box.ruleCount()) {
            return new Hit(Kind.ADD, box.index(), -1);
        }
        int chipY = box.bodyTop() + TILE_PAD + row * (TILE_H + TILE_GAP) + TILE_CHIP_Y;
        if (localY >= chipY && localY < chipY + CHIP) {
            int x = tileX(this.viewport, col) + TILE_INSET;
            if (inside(localX, x, CHIP)) {
                return new Hit(Kind.CHIP_DIR, box.index(), tile);
            }
            if (inside(localX, x + CHIP + GAP, CHIP)) {
                return new Hit(Kind.CHIP_FACE, box.index(), tile);
            }
            if (inside(localX, x + (CHIP + GAP) * 2, CHIP)) {
                return new Hit(Kind.CHIP_MODE, box.index(), tile);
            }
        }
        return new Hit(Kind.RULE, box.index(), tile);
    }

    private static boolean inside(int value, int start, int length) {
        return value >= start && value < start + length;
    }

    /** 列表式行区域左边缘（屏幕坐标）。 */
    static int rowAreaX(Viewport viewport) {
        return viewport.x() + STATUS_W;
    }

    /** 块内容右边缘（屏幕坐标）。 */
    static int contentRight(Viewport viewport) {
        return viewport.x() + viewport.width() - PAD;
    }

    /** 规则行图标凹槽 x。 */
    static int slotX(Viewport viewport) {
        return rowAreaX(viewport) + PAD + DIR_STRIPE_W + GAP;
    }

    /** 规则行名字 x。 */
    static int nameX(Viewport viewport) {
        return slotX(viewport) + SLOT + GAP;
    }

    /** 速率列 x。 */
    static int rateX(Viewport viewport) {
        return contentRight(viewport) - RATE_W;
    }

    /**
     * 三枚 chip 的 x（列表式）。
     *
     * @param index 0 方向 / 1 面 / 2 模式
     * @return 屏幕 x
     */
    static int chipX(Viewport viewport, int index) {
        int right = rateX(viewport) - GAP;
        int mode = right - CHIP;
        int face = mode - GAP - CHIP;
        int dir = face - GAP - CHIP;
        return switch (index) {
            case 0 -> dir;
            case 1 -> face;
            default -> mode;
        };
    }

    /** 磁贴 x。 */
    static int tileX(Viewport viewport, int column) {
        return rowAreaX(viewport) + TILE_PAD + column * (TILE_W + TILE_GAP);
    }

    /** 列表式某一行的屏幕 y。 */
    static int rowY(Viewport viewport, BlockBox box, int rule, int scroll) {
        return viewport.y() - scroll + box.bodyTop() + rule * ROW_H;
    }

    /** 磁贴区某一行的屏幕 y。 */
    static int tileRowY(Viewport viewport, BlockBox box, int row, int scroll) {
        return viewport.y() - scroll + box.bodyTop() + TILE_PAD + row * (TILE_H + TILE_GAP);
    }

    /** 屏幕 y：块相对坐标转屏幕坐标。 */
    static int screenY(Viewport viewport, int localTop, int scroll) {
        return viewport.y() - scroll + localTop;
    }
}
