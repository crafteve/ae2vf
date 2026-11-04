package io.github.crafteve.idlefactory.client.screen.terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.client.screen.FactoryLamp;

/**
 * 节点管理内容区的排版与命中：唯一的像素来源。
 *
 * <p>节点卡片复用被动 IO 终端的卡片层级：整张节点用 {@code FactoryCard.Level.MACHINE}，展开的
 * 机器行用 {@code Level.IO}。坐标相对内容区，绘制、命中、tooltip 都从这里取同一份结果；滚动只在
 * 取值时加 {@code scroll}，不重建排版。</p>
 *
 * <p>节点行结构：左边图标 + 名字 + 状态点，中间 provider 三格凹底，右边编辑 / 删除成对。
 * 展开后机器行只在行尾放定位；节点行按钮不随展开消失。</p>
 */
final class FactoryNodeLayout {
    /** 节点行高。 */
    static final int NODE_ROW_H = 20;
    /** 机器行高。 */
    static final int MEMBER_ROW_H = 16;
    /** 机器行间距。 */
    static final int MEMBER_GAP = 1;
    /** 节点行与第一台机器之间的空档。 */
    static final int BODY_GAP = 1;
    /** 节点块间距。 */
    static final int BLOCK_GAP = 2;
    /** 卡片左侧状态灯宽。 */
    static final int STATUS_W = FactoryLamp.WIDTH;
    /** 图标凹槽。 */
    static final int SLOT = 18;
    /** 堆叠徽标尺寸。 */
    static final int BADGE_W = 11;
    static final int BADGE_H = 9;
    /** provider 键帽尺寸与间隔。 */
    static final int KEY_W = 14;
    static final int KEY_H = 16;
    static final int KEY_GAP = 1;
    /** provider 凹底内边距。 */
    static final int TROUGH_PAD = 1;
    /** provider 凹底宽高。 */
    static final int TROUGH_W = TROUGH_PAD * 2 + KEY_W * 3 + KEY_GAP * 2;
    static final int TROUGH_H = 18;
    /** 编辑 / 删除键帽。 */
    static final int ACTION = 16;
    /** 展开后的“还有 N 台”汇总行高。 */
    static final int MORE_ROW_H = 14;
    /** 展开时先显示几台机器，超出折叠。 */
    static final int EXPANDED_LIMIT = 3;
    /** 卡片右边缘相对视口的留白，给外侧阴影。 */
    static final int CARD_RIGHT_INSET = 1;
    /** 机器行卡片内缩。 */
    static final int MEMBER_INSET = 2;
    /** 机器行资源图标边长。 */
    static final int MEMBER_ICON = 12;

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
     * 一个节点块在内容坐标系里的位置。
     *
     * @param index        节点下标
     * @param top          上边缘
     * @param memberTop    第一台机器行上边缘
     * @param shownMembers 实际画出来的机器行数
     * @param totalMembers 节点里的机器总数
     * @param expanded     是否展开
     * @param bottom       下边缘
     */
    record NodeBox(int index, int top, int memberTop, int shownMembers, int totalMembers,
            boolean expanded, int bottom) {
    }

    /** 命中种类。 */
    enum Kind {
        /** 节点行主体。 */
        NODE,
        /** 节点名字。 */
        NAME,
        /** 阻挡。 */
        BLOCKING,
        /** 倍增。 */
        MULTIPLIER,
        /** 逐台 / 轮询。 */
        DISPATCH,
        /** 编辑节点。 */
        EDIT,
        /** 删除节点。 */
        DELETE,
        /** 机器行主体。 */
        MEMBER,
        /** 机器行尾的定位。 */
        LOCATE,
        /** “还有 N 台”汇总行。 */
        MORE
    }

    /**
     * 一次命中。
     *
     * @param kind   种类
     * @param node   节点下标
     * @param member 机器下标；节点级命中为 -1
     */
    record Hit(Kind kind, int node, int member) {
    }

    private final List<NodeBox> boxes;
    private final Viewport viewport;
    private final int contentHeight;

    private FactoryNodeLayout(List<NodeBox> boxes, Viewport viewport, int contentHeight) {
        this.boxes = boxes;
        this.viewport = viewport;
        this.contentHeight = contentHeight;
    }

    /**
     * 排版一次。
     *
     * @param rows     模型里的节点行
     * @param viewport 内容视口
     * @param expanded 当前展开的节点 id
     * @return 排版
     */
    static FactoryNodeLayout of(List<FactoryNodeModel.NodeRow> rows, Viewport viewport,
            Set<UUID> expanded, Set<UUID> expandedAll) {
        List<NodeBox> boxes = new ArrayList<>(rows.size());
        int cursor = 0;
        for (int i = 0; i < rows.size(); i++) {
            FactoryNodeModel.NodeRow row = rows.get(i);
            boolean open = expanded.contains(row.nodeId());
            int total = row.members().size();
            int shown = !open ? 0
                    : expandedAll.contains(row.nodeId()) ? total
                            : Math.min(total, EXPANDED_LIMIT);
            int height = height(open, total, shown);
            boxes.add(new NodeBox(i, cursor, cursor + NODE_ROW_H + BODY_GAP, shown, total, open,
                    cursor + height));
            cursor += height + BLOCK_GAP;
        }
        int contentHeight = boxes.isEmpty() ? 0 : cursor - BLOCK_GAP;
        return new FactoryNodeLayout(List.copyOf(boxes), viewport, contentHeight);
    }

    private static int height(boolean open, int total, int shown) {
        int height = NODE_ROW_H;
        if (!open || total == 0) {
            return height;
        }
        height += BODY_GAP + shown * MEMBER_ROW_H + Math.max(0, shown - 1) * MEMBER_GAP;
        if (total > shown) {
            height += MEMBER_GAP + MORE_ROW_H;
        }
        return height;
    }

    /** 块位置列表。 */
    List<NodeBox> boxes() {
        return this.boxes;
    }

    /** 内容总高。 */
    int contentHeight() {
        return this.contentHeight;
    }

    /** 视口。 */
    Viewport viewport() {
        return this.viewport;
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

    /**
     * 命中哪个元素。
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
        for (NodeBox box : this.boxes) {
            if (localY < box.top() || localY >= box.bottom()) {
                continue;
            }
            if (localY < box.top() + NODE_ROW_H) {
                return hitNodeRow(this.viewport, box, localX, localY);
            }
            if (!box.expanded() || box.totalMembers() == 0) {
                return new Hit(Kind.NODE, box.index(), -1);
            }
            int rel = localY - box.memberTop();
            if (rel < 0) {
                return new Hit(Kind.NODE, box.index(), -1);
            }
            int pitch = MEMBER_ROW_H + MEMBER_GAP;
            int member = rel / pitch;
            if (member < box.shownMembers()) {
                int rowY = box.memberTop() + member * pitch;
                if (localY >= rowY + MEMBER_ROW_H) {
                    return new Hit(Kind.MEMBER, box.index(), member);
                }
                if (inside(localX, locateX(this.viewport), ACTION)) {
                    return new Hit(Kind.LOCATE, box.index(), member);
                }
                return new Hit(Kind.MEMBER, box.index(), member);
            }
            return new Hit(Kind.MORE, box.index(), -1);
        }
        return null;
    }

    private static Hit hitNodeRow(Viewport viewport, NodeBox box, int localX, int localY) {
        int keyY = box.top() + (NODE_ROW_H - KEY_H) / 2;
        if (localY >= keyY - 1 && localY < keyY + KEY_H + 1) {
            if (inside(localX, keyX(viewport, 0), KEY_W)) {
                return new Hit(Kind.BLOCKING, box.index(), -1);
            }
            if (inside(localX, keyX(viewport, 1), KEY_W)) {
                return new Hit(Kind.MULTIPLIER, box.index(), -1);
            }
            if (inside(localX, keyX(viewport, 2), KEY_W)) {
                return new Hit(Kind.DISPATCH, box.index(), -1);
            }
            if (inside(localX, editX(viewport), ACTION)) {
                return new Hit(Kind.EDIT, box.index(), -1);
            }
            if (inside(localX, deleteX(viewport), ACTION)) {
                return new Hit(Kind.DELETE, box.index(), -1);
            }
        }
        if (inside(localX, nameX(viewport), nameWidth(viewport))) {
            return new Hit(Kind.NAME, box.index(), -1);
        }
        return new Hit(Kind.NODE, box.index(), -1);
    }

    private static boolean inside(int value, int start, int length) {
        return value >= start && value < start + length;
    }

    /** 节点卡片左边缘。 */
    static int cardX(Viewport viewport) {
        return viewport.x();
    }

    /** 节点卡片宽度（右 1px 留给外阴影）。 */
    static int cardWidth(Viewport viewport) {
        return viewport.width() - CARD_RIGHT_INSET;
    }

    /** 节点卡片右边缘。 */
    static int cardRight(Viewport viewport) {
        return cardX(viewport) + cardWidth(viewport);
    }

    /** 名字左边缘。 */
    static int nameX(Viewport viewport) {
        return cardX(viewport) + 1 + STATUS_W + 1 + SLOT + 1;
    }

    /** provider 凹底左边缘（屏幕坐标）。 */
    static int troughX(Viewport viewport) {
        return editX(viewport) - 2 - TROUGH_W;
    }

    /** provider 凹底右边缘。 */
    static int troughRight(Viewport viewport) {
        return editX(viewport) - 2;
    }

    /** 第 index 枚 provider 键帽左边缘。 */
    static int keyX(Viewport viewport, int index) {
        return troughX(viewport) + TROUGH_PAD + index * (KEY_W + KEY_GAP);
    }

    /** 编辑键左边缘。 */
    static int editX(Viewport viewport) {
        return deleteX(viewport) - 1 - ACTION;
    }

    /** 删除键左边缘。 */
    static int deleteX(Viewport viewport) {
        return cardRight(viewport) - 2 - ACTION;
    }

    /** 名字可用宽度。 */
    static int nameWidth(Viewport viewport) {
        return troughX(viewport) - 2 - nameX(viewport);
    }

    /** 机器行卡片左边缘。 */
    static int memberX(Viewport viewport) {
        return cardX(viewport) + MEMBER_INSET;
    }

    /** 机器行卡片宽度。 */
    static int memberWidth(Viewport viewport) {
        return cardWidth(viewport) - MEMBER_INSET * 2;
    }

    /** 机器行图标左边缘。 */
    static int memberIconX(Viewport viewport) {
        return memberX(viewport) + 3;
    }

    /** 机器行文字左边缘。 */
    static int memberTextX(Viewport viewport) {
        return memberIconX(viewport) + MEMBER_ICON + 2;
    }

    /** 机器行定位键左边缘。 */
    static int locateX(Viewport viewport) {
        return memberX(viewport) + memberWidth(viewport) - 2 - ACTION;
    }

    /** 机器行状态点 x。 */
    static int memberDotX(Viewport viewport) {
        return locateX(viewport) - 4 - 3;
    }

    /** 机器行某一行的屏幕 y。 */
    static int memberRowY(Viewport viewport, NodeBox box, int member, int scroll) {
        return viewport.y() - scroll + box.memberTop() + member * (MEMBER_ROW_H + MEMBER_GAP);
    }

    /** “还有 N 台”汇总行的屏幕 y。 */
    static int moreRowY(Viewport viewport, NodeBox box, int scroll) {
        return viewport.y() - scroll + box.memberTop()
                + box.shownMembers() * (MEMBER_ROW_H + MEMBER_GAP);
    }

    /** 块相对坐标转屏幕 y。 */
    static int screenY(Viewport viewport, int localTop, int scroll) {
        return viewport.y() - scroll + localTop;
    }
}
