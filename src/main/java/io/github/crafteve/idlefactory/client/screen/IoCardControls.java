package io.github.crafteve.idlefactory.client.screen;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.resource.ResourceKind;
import io.github.crafteve.idlefactory.factory.resource.ResourceSpec;

import net.minecraft.core.Direction;

/**
 * IO 配置卡内部控件的几何与命中。
 *
 * <p>卡片布局：顶部信息区（图标、名字、速率、删除 x），下面两行面按钮（任/上/下/北 与
 * 南/东/西），第二行/第一行末尾放黄色匹配按钮。绘制、命中、tooltip 都从这里取矩形，
 * 避免三处各算一份。</p>
 */
final class IoCardControls {
    /** 面顺序：任意 + 六向。 */
    static final Direction[] FACES = {
            null, Direction.UP, Direction.DOWN, Direction.NORTH,
            Direction.SOUTH, Direction.EAST, Direction.WEST
    };
    /** 面按钮单字。 */
    static final String[] FACE_LABELS = { "任", "上", "下", "北", "南", "东", "西" };

    /** 命中类型。 */
    enum Kind {
        /** 删除规则。 */
        DELETE,
        /** 设置某个面。 */
        FACE,
        /** 切换模糊/精确。 */
        MATCH,
        /** 卡片空白处。 */
        BODY,
        /** 空卡：放入资源。 */
        EMPTY,
        /** 没命中。 */
        NONE
    }

    /**
     * 一次命中。
     *
     * @param kind      类型
     * @param faceIndex 面下标；仅 {@link Kind#FACE} 有意义
     */
    record Hit(Kind kind, int faceIndex) {
    }

    private IoCardControls() {
        // 纯静态工具类
    }

    /**
     * 面按钮矩形。
     *
     * @param cardX 卡片左边缘
     * @param cardY 卡片上边缘
     * @param index 面下标 0..6
     * @return 矩形
     */
    static Rect faceRect(int cardX, int cardY, int index) {
        int row = index / 4;
        int col = index % 4;
        int x = cardX + FactoryLayout.IO_CARD_PAD
                + col * (FactoryLayout.IO_FACE_BTN_W + FactoryLayout.IO_FACE_GAP);
        int y = cardY + FactoryLayout.IO_CARD_TOP_H + 2 + row * 14;
        return new Rect(x, y, FactoryLayout.IO_FACE_BTN_W, FactoryLayout.IO_FACE_BTN_H);
    }

    /**
     * 匹配按钮矩形；放在第一行面按钮之后。
     *
     * @param cardX 卡片左边缘
     * @param cardY 卡片上边缘
     * @return 矩形
     */
    static Rect matchRect(int cardX, int cardY) {
        int x = cardX + FactoryLayout.IO_CARD_PAD
                + 4 * (FactoryLayout.IO_FACE_BTN_W + FactoryLayout.IO_FACE_GAP);
        int y = cardY + FactoryLayout.IO_CARD_TOP_H + 2;
        return new Rect(x, y, FactoryLayout.IO_FACE_BTN_W, FactoryLayout.IO_FACE_BTN_H);
    }

    /**
     * 删除按钮矩形。
     *
     * @param cardX 卡片左边缘
     * @param cardY 卡片上边缘
     * @param cardW 卡片宽
     * @return 矩形
     */
    static Rect deleteRect(int cardX, int cardY, int cardW) {
        return new Rect(cardX + cardW - FactoryLayout.IO_DELETE_SIZE - 3, cardY + 3,
                FactoryLayout.IO_DELETE_SIZE, FactoryLayout.IO_DELETE_SIZE);
    }

    /**
     * 命中卡片控件。
     *
     * @param cardX 卡片左边缘
     * @param cardY 卡片上边缘
     * @param cardW 卡片宽
     * @param empty 是否空卡
     * @param spec  规则；空卡传 {@code null}
     * @param mx    鼠标 x
     * @param my    鼠标 y
     * @return 命中
     */
    static Hit hit(int cardX, int cardY, int cardW, boolean empty,
            @Nullable ResourceSpec spec, double mx, double my) {
        if (!inside(mx, my, cardX, cardY, cardW, FactoryLayout.IO_CARD_H)) {
            return new Hit(Kind.NONE, -1);
        }
        if (empty) {
            return new Hit(Kind.EMPTY, -1);
        }
        if (inside(mx, my, deleteRect(cardX, cardY, cardW))) {
            return new Hit(Kind.DELETE, -1);
        }
        for (int i = 0; i < FACES.length; i++) {
            if (inside(mx, my, faceRect(cardX, cardY, i))) {
                return new Hit(Kind.FACE, i);
            }
        }
        if (spec != null && matchVisible(spec) && inside(mx, my, matchRect(cardX, cardY))) {
            return new Hit(matchEnabled(spec) ? Kind.MATCH : Kind.NONE, -1);
        }
        return new Hit(Kind.BODY, -1);
    }

    /**
     * 匹配按钮是否可点：只有无组件物品能在模糊/精确间切换。
     *
     * @param spec 规则
     * @return 可点时为 {@code true}
     */
    static boolean matchEnabled(ResourceSpec spec) {
        return spec.key().kind() == ResourceKind.ITEM && !spec.key().hasComponents();
    }

    /**
     * 是否显示匹配按钮：物品才显示；流体/FE 固定精确。
     *
     * @param spec 规则
     * @return 显示时为 {@code true}
     */
    static boolean matchVisible(ResourceSpec spec) {
        return spec.key().kind() == ResourceKind.ITEM;
    }

    /**
     * 匹配按钮文案。
     *
     * @param spec 规则
     * @return 模 / 精
     */
    static String matchLabel(ResourceSpec spec) {
        return spec.fuzzy() ? "模" : "精";
    }

    /**
     * 面按钮是否选中。
     *
     * @param spec  规则
     * @param index 面下标
     * @return 选中时为 {@code true}
     */
    static boolean faceSelected(ResourceSpec spec, int index) {
        return spec.face() == FACES[index];
    }

    private static boolean inside(double mouseX, double mouseY, Rect rect) {
        return inside(mouseX, mouseY, rect.x(), rect.y(), rect.width(), rect.height());
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width,
            int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    /**
     * 简单矩形。
     *
     * @param x      左边缘
     * @param y      上边缘
     * @param width  宽
     * @param height 高
     */
    record Rect(int x, int y, int width, int height) {
    }
}
