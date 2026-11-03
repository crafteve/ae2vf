package io.github.crafteve.idlefactory.client.screen.terminal;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.client.screen.FactoryLamp;
import io.github.crafteve.idlefactory.menu.FactoryPassiveIoView;

/**
 * 被动 IO 内容区的排版与命中：唯一的像素来源。
 *
 * <p>块从内容顶部开始顺序铺开，坐标相对内容区（不叠加视口原点与滚动 Offset），绘制、命中、
 * tooltip 和 JEI 都从这里取同一份结果。滚动只在取值时加 {@code scroll}，不重建排版。</p>
 *
 * <p>块内结构（列表式）：机器行 20 → 规则行 16×n，行间不留缝、奇偶行交替着色；磁贴式：机器行 20 → 磁贴区
 * （上边紧贴机器行，下边留 2px 内边距，77×34 两列，列间隔 2）。块与块之间留 2px，
 * 刚好容下 1px 外阴影。</p>
 */
final class FactoryPassiveIoLayout {
    /** 规则行高；16px 时 13px chip 上下各余 1~2px。 */
    static final int ROW_H = 16;
    /** 列表规则行之间的间距：行底交替着色，不再留缝。 */
    static final int ROW_GAP = 0;
    /** 机器行高。 */
    static final int MACHINE_ROW_H = 20;
    /** 块间距：留 2px 刚好一件 1px 外阴影 + 1px 空隙。 */
    static final int BLOCK_GAP = 2;
    /**
     * 机器行与下面第一张卡 / 磁贴之间的空档。
     *
     * <p>空档 1px + 卡片自己的 1px 上亮边，视觉上正好 2px；写 2 会变成 3px，
     * 因为卡片亮边和空档都落在同一块浅色底上。</p>
     */
    static final int BODY_GAP = 1;
    /** 块左侧状态灯宽；宽度由 {@link FactoryLamp} 给出，排版只引用不另写数值。 */
    static final int STATUS_W = FactoryLamp.WIDTH;
    /** 块内左右内边距。 */
    static final int PAD = 2;
    /** 规则行 / 磁贴卡片相对块左边缘的内缩；卡片与块之间留 1px 让外阴影收口。 */
    static final int ROW_INSET = 1;
    /** 规则行内容左缩进：卡片内缩 + 内边距。 */
    static final int CONTENT_INSET = ROW_INSET + PAD;
    /** 机器行 / 磁贴图标凹槽边长。 */
    static final int SLOT = 18;
    /** 列表规则行资源图标边长；比凹槽小，行内不再画槽位底。 */
    static final int RULE_ICON = 12;
    /** 行内元素间距。 */
    static final int GAP = 1;
    /** 三枚 chip 的边长：1px 立体边 + 1px 内边距 + 9px 单字，中文才不贴边。 */
    static final int CHIP = 13;
    /** chip 在行内的纵向偏移：让 chip 里的字和行名同一基线，宁可下沿少 1px。 */
    static final int CHIP_INSET_Y = (ROW_H - CHIP + 1) / 2;
    /** 速率列宽。 */
    static final int RATE_W = 38;
    /** 磁贴宽。 */
    static final int TILE_W = 77;
    /** 磁贴高。 */
    static final int TILE_H = 34;
    /** 磁贴列间隔。 */
    static final int TILE_GAP = 2;
    /** 磁贴区内边距。 */
    static final int TILE_PAD = 2;
    /** 机器行徽标宽 / 高。 */
    static final int BADGE_W = 14;
    static final int BADGE_H = 11;
    /** 磁贴第二行（chip 行）相对磁贴顶部的偏移。 */
    static final int TILE_CHIP_Y = 20;
    /** 磁贴内容左缩进：1px 卡片边 + 1px 内边距。 */
    static final int TILE_INSET = 2;
    /** 磁贴里名字的 x 偏移：图标凹槽之后再空 2px。 */
    static final int TILE_NAME_X = TILE_INSET + SLOT + 2;

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
     * <p>没有单独的「新增行」：整块卡片就是拖入目标，空白区由 {@link Kind#ADD} 命中。</p>
     *
     * @param index     块下标
     * @param top       上边缘
     * @param bodyTop   块体上边缘（机器行之下）
     * @param bottom    下边缘
     * @param ruleCount 这块有几条规则
     */
    record BlockBox(int index, int top, int bodyTop, int bottom, int ruleCount) {
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
        /** 机器卡片的空白区：行缝、规则之后、空机器的卡底；拖入资源即在这里新建规则。 */
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
            int bodyTop = cursor + MACHINE_ROW_H + BODY_GAP;
            boxes.add(new BlockBox(i, cursor, bodyTop, cursor + height,
                    block.rules().size()));
            cursor += height + BLOCK_GAP;
        }
        int contentHeight = boxes.isEmpty() ? 0 : cursor - BLOCK_GAP;
        return new FactoryPassiveIoLayout(List.copyOf(boxes), viewport, view, contentHeight);
    }

    /**
     * 一块的高度。
     *
     * <p>没有规则时只保留机器行：整张卡片就是拖入目标，不再额外留一条空行。</p>
     *
     * @param block 块
     * @param view  视图
     * @return 像素高度
     */
    static int blockHeight(FactoryPassiveIoModel.BlockView block, FactoryPassiveIoView view) {
        int rules = block.rules().size();
        if (rules == 0) {
            return MACHINE_ROW_H;
        }
        if (view == FactoryPassiveIoView.LIST) {
            return MACHINE_ROW_H + BODY_GAP + rules * ROW_H + (rules - 1) * ROW_GAP;
        }
        int rows = (rules + 1) / 2;
        return MACHINE_ROW_H + BODY_GAP + rows * TILE_H + (rows - 1) * TILE_GAP + TILE_PAD;
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
                // 机器行与下面卡片之间的 2px 缝隙不属于任何元素。
                if (localY >= box.top() + MACHINE_ROW_H) {
                    return null;
                }
                return new Hit(Kind.MACHINE, box.index(), -1);
            }
            return this.view == FactoryPassiveIoView.LIST
                    ? hitList(box, localX, localY)
                    : hitTiles(box, localX, localY);
        }
        return null;
    }

    private Hit hitList(BlockBox box, int localX, int localY) {
        int pitch = ROW_H + ROW_GAP;
        int rule = (localY - box.bodyTop()) / pitch;
        // 行缝、最后一行之下、空机器的卡底都属于拖入区：没有单独的虚线新增行。
        if (rule >= box.ruleCount() || localY >= box.bodyTop() + rule * pitch + ROW_H) {
            return new Hit(Kind.ADD, box.index(), -1);
        }
        int rowY = box.bodyTop() + rule * pitch;
        int chipY = rowY + CHIP_INSET_Y;
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

    private Hit hitTiles(BlockBox box, int localX, int localY) {
        int relY = localY - box.bodyTop();
        if (relY < 0) {
            return new Hit(Kind.ADD, box.index(), -1);
        }
        int row = relY / (TILE_H + TILE_GAP);
        int inTileY = relY - row * (TILE_H + TILE_GAP);
        if (inTileY >= TILE_H) {
            return new Hit(Kind.ADD, box.index(), -1);
        }
        int col = inside(localX, tileX(this.viewport, 0), TILE_W) ? 0
                : inside(localX, tileX(this.viewport, 1), TILE_W) ? 1 : -1;
        int tile = col < 0 ? -1 : row * 2 + col;
        // 磁贴缝、最后一块磁贴之后、空机器的卡底同样属于拖入区。
        if (tile < 0 || tile >= box.ruleCount()) {
            return new Hit(Kind.ADD, box.index(), -1);
        }
        int chipY = box.bodyTop() + row * (TILE_H + TILE_GAP) + TILE_CHIP_Y;
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

    /**
     * 机器行内容左边缘（屏幕坐标）。
     *
     * <p>机器行是机器大卡片顶部的一条深色带，状态灯顶掉卡片最左一条竖边；内容从灯的右侧
     * 再空 1px 开始。</p>
     */
    static int headerX(Viewport viewport) {
        return viewport.x() + ROW_INSET + STATUS_W + GAP;
    }

    /**
     * 规则 / 磁贴主体左边缘（屏幕坐标）。
     *
     * <p>机器大卡片铺到内容区最左边，规则行与磁贴再内缩 {@link #ROW_INSET}，
     * 给卡片外阴影留 1px。</p>
     */
    static int bodyX(Viewport viewport) {
        return viewport.x();
    }

    /** 块内容右边缘（屏幕坐标）。 */
    static int contentRight(Viewport viewport) {
        return viewport.x() + viewport.width() - PAD;
    }

    /** 规则行图标凹槽 x：卡片内缩 + 内边距。 */
    static int slotX(Viewport viewport) {
        return bodyX(viewport) + CONTENT_INSET;
    }

    /** 规则行名字 x。 */
    static int nameX(Viewport viewport) {
        return slotX(viewport) + RULE_ICON + GAP;
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
        return bodyX(viewport) + TILE_PAD + column * (TILE_W + TILE_GAP);
    }

    /** 列表式某一行的屏幕 y。 */
    static int rowY(Viewport viewport, BlockBox box, int rule, int scroll) {
        return viewport.y() - scroll + box.bodyTop() + rule * (ROW_H + ROW_GAP);
    }

    /** 磁贴区某一行的屏幕 y；第一行紧贴机器行下方，不再叠一层内边距。 */
    static int tileRowY(Viewport viewport, BlockBox box, int row, int scroll) {
        return viewport.y() - scroll + box.bodyTop() + row * (TILE_H + TILE_GAP);
    }

    /** 屏幕 y：块相对坐标转屏幕坐标。 */
    static int screenY(Viewport viewport, int localTop, int scroll) {
        return viewport.y() - scroll + localTop;
    }
}
