package io.github.crafteve.idlefactory.client.screen;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.capability.CapabilityKind;
import io.github.crafteve.idlefactory.factory.machine.ResourceSpec;

import net.minecraft.core.Direction;
import appeng.api.stacks.AEItemKey;

/**
 * IO 配置卡内部控件的几何与命中。
 *
 * <p>卡片沿用 26px 的紧凑样式：第一行是槽位、名称与右对齐速率；第二行依次是面按钮、
 * 匹配按钮与状态文字。面按钮左键切到下一个面、右键切回上一个面；匹配按钮点击在精确 /
 * 模糊之间切换。绘制、命中、tooltip 都从这里取矩形，避免三处各算一份。</p>
 */
final class FactoryIoCardControls {
    /** 面顺序：任意 + 六向。 */
    static final Direction[] FACES = {
            null, Direction.UP, Direction.DOWN, Direction.NORTH,
            Direction.SOUTH, Direction.EAST, Direction.WEST
    };
    /** 面按钮单字。 */
    static final String[] FACE_LABELS = { "任", "上", "下", "北", "南", "东", "西" };

    /** 命中类型。 */
    enum Kind {
        /** 面按钮。 */
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
     * @param kind 类型
     */
    record Hit(Kind kind) {
    }

    private FactoryIoCardControls() {
        // 纯静态工具类
    }

    /**
     * 面按钮矩形；固定在卡片第二行左侧。
     *
     * @param cardX 卡片左边缘
     * @param cardY 卡片上边缘
     * @return 矩形
     */
    static Rect faceRect(int cardX, int cardY) {
        return new Rect(cardX + FactoryLayout.IO_CARD_TEXT_X,
                cardY + FactoryLayout.IO_CARD_LINE2_Y,
                FactoryLayout.IO_CARD_FACE_W, FactoryLayout.IO_CARD_BTN_H);
    }

    /**
     * 匹配按钮矩形；紧跟面按钮。
     *
     * @param cardX 卡片左边缘
     * @param cardY 卡片上边缘
     * @return 矩形
     */
    static Rect matchRect(int cardX, int cardY) {
        Rect face = faceRect(cardX, cardY);
        return new Rect(face.x() + face.width() + FactoryLayout.IO_CARD_BTN_GAP,
                face.y(), FactoryLayout.IO_CARD_MATCH_W, FactoryLayout.IO_CARD_BTN_H);
    }

    /**
     * 状态文字的起始 x。
     *
     * <p>物品有匹配按钮时从匹配按钮右侧开始；流体 / FE 不显示匹配按钮，直接从面按钮
     * 右侧开始，避免留下空洞。</p>
     *
     * @param cardX 卡片左边缘
     * @param spec  规则
     * @return 状态文字左边缘
     */
    static int reasonX(int cardX, ResourceSpec spec) {
        int x = cardX + FactoryLayout.IO_CARD_TEXT_X + FactoryLayout.IO_CARD_FACE_W
                + FactoryLayout.IO_CARD_REASON_GAP;
        if (matchVisible(spec)) {
            x += FactoryLayout.IO_CARD_BTN_GAP + FactoryLayout.IO_CARD_MATCH_W;
        }
        return x;
    }

    /**
     * 当前面在 {@link #FACES} 里的下标。
     *
     * @param face 面；{@code null} 表示任意
     * @return 下标 0..6
     */
    static int faceIndex(@Nullable Direction face) {
        for (int i = 0; i < FACES.length; i++) {
            if (FACES[i] == face) {
                return i;
            }
        }
        return 0;
    }

    /**
     * 面按钮文案。
     *
     * @param spec 规则
     * @return 任 / 上 / 下 / 北 / 南 / 东 / 西
     */
    static String faceLabel(ResourceSpec spec) {
        return FACE_LABELS[faceIndex(spec.face())];
    }

    /**
     * 在七个面之间循环。
     *
     * @param current 当前面；{@code null} 表示任意
     * @param delta   正数下一个、负数上一个
     * @return 新面
     */
    @Nullable
    static Direction nextFace(@Nullable Direction current, int delta) {
        int index = Math.floorMod(faceIndex(current) + delta, FACES.length);
        return FACES[index];
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
        if (!inside(mx, my, cardX, cardY, cardW, FactoryLayout.IO_CELL_H)) {
            return new Hit(Kind.NONE);
        }
        if (empty) {
            return new Hit(Kind.EMPTY);
        }
        if (inside(mx, my, faceRect(cardX, cardY))) {
            return new Hit(Kind.FACE);
        }
        if (spec != null && matchVisible(spec) && inside(mx, my, matchRect(cardX, cardY))) {
            return new Hit(Kind.MATCH);
        }
        return new Hit(Kind.BODY);
    }

    /**
     * 是否显示匹配按钮：物品才显示；流体/FE 固定精确。
     *
     * <p>带组件物品也可以切换模糊：模糊只按注册名匹配所有变体，组件快照仍用于精确模式。</p>
     *
     * @param spec 规则
     * @return 显示时为 {@code true}
     */
    static boolean matchVisible(ResourceSpec spec) {
        return (spec.key() instanceof AEItemKey);
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
