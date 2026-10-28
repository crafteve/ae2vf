package io.github.crafteve.idlefactory.client.screen;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder;
import io.github.crafteve.idlefactory.factory.resource.ResourceSpec;

import net.minecraft.client.gui.Font;

/**
 * 展开 IO 面板的扁平布局：输入段、输入配置卡/空卡、输出段、输出配置卡/空卡。
 *
 * <p>卡片固定高（{@link FactoryLayout#IO_CARD_H}），内容超过可见高度时用像素滚动；绘制方
 * 按 {@link Detail#scroll()} 平移，命中测试用同一份绝对坐标并拒绝面板外的位置。空卡永远
 * 在每段末尾，作为“拖资源进来新增规则”的放置目标。</p>
 *
 * <p>本类只算几何，不绘制；绘制在 {@link IoDetailRender}，控件命中在
 * {@link IoCardControls}。</p>
 */
final class IoDetailLayout {
    /** 卡片之间的间距。 */
    static final int GAP = FactoryLayout.IO_GAP;

    /**
     * 一个段头。
     *
     * @param input   是否输入
     * @param headerY 绝对 y
     * @param rules   规则条数（不含空卡）
     * @param rate    该侧总速率
     */
    record Section(boolean input, int headerY, int rules, long rate) {
    }

    /**
     * 一张卡。
     *
     * @param x         左边缘
     * @param y         上边缘
     * @param width     宽
     * @param input     是否输入
     * @param ruleIndex 规则下标；空卡时等于该侧规则数
     * @param empty     是否空卡
     */
    record Card(int x, int y, int width, boolean input, int ruleIndex, boolean empty) {
    }

    /**
     * 一次滚动布局。
     *
     * @param panelX      面板左边缘
     * @param panelY      面板上边缘
     * @param panelW      面板宽
     * @param panelH      面板高
     * @param contentH    内容总高（不含上下内边距）
     * @param maxScroll   最大滚动像素
     * @param scroll      当前滚动像素（已夹紧）
     * @param sections    两个段头
     * @param cards       全部卡片（含面板外的）
     */
    record Detail(int panelX, int panelY, int panelW, int panelH, int contentH,
            int maxScroll, int scroll, List<Section> sections, List<Card> cards) {
        Detail {
            sections = List.copyOf(sections);
            cards = List.copyOf(cards);
        }
    }

    /**
     * 排一个位置。
     *
     * @param x         相对内容区左边缘
     * @param rowY      相对段内容顶部
     * @param width     卡宽
     * @param ruleIndex 规则下标
     * @param empty     是否空卡
     */
    private record Placement(int x, int rowY, int width, int ruleIndex, boolean empty) {
    }

    private IoDetailLayout() {
        // 纯静态工具类
    }

    /**
     * 展开面板的完整所需高度。
     *
     * @param layout  布局
     * @param machine 机器视图
     * @param font    字体
     * @return 像素高度
     */
    static int requiredHeight(FactoryLayout layout, FactorySnapshotBuilder.MachineView machine,
            Font font) {
        List<Placement> input = place(layout, machine.inputs(), font);
        List<Placement> output = place(layout, machine.outputs(), font);
        return FactoryLayout.IO_PANEL_PAD * 2
                + sectionHeight(input) + FactoryLayout.IO_SECTION_GAP + sectionHeight(output);
    }

    /**
     * 计算当前滚动下的绝对坐标。
     *
     * @param layout   布局
     * @param machine  机器视图
     * @param topY     面板上边缘
     * @param height   面板高度（已夹紧）
     * @param font     字体
     * @param scrollPx 期望滚动像素
     * @return 布局
     */
    static Detail layout(FactoryLayout layout, FactorySnapshotBuilder.MachineView machine,
            int topY, int height, Font font, int scrollPx) {
        int panelX = layout.leftX + 2;
        int panelW = layout.ioPanelWidth();
        int innerW = layout.ioInnerWidth();
        int cardW = layout.ioCellWidth();
        int contentX = panelX + FactoryLayout.IO_PANEL_PAD;

        List<Placement> input = place(layout, machine.inputs(), font);
        List<Placement> output = place(layout, machine.outputs(), font);
        int inputH = sectionHeight(input);
        int outputH = sectionHeight(output);
        int contentH = inputH + FactoryLayout.IO_SECTION_GAP + outputH;
        int maxScroll = Math.max(0, contentH + FactoryLayout.IO_PANEL_PAD * 2 - height);
        int scroll = Math.max(0, Math.min(scrollPx, maxScroll));
        int baseY = topY + FactoryLayout.IO_PANEL_PAD - scroll;

        List<Section> sections = new ArrayList<>(2);
        List<Card> cards = new ArrayList<>();
        addSection(sections, cards, true, baseY, contentX, innerW, input,
                machine.inputs().size(), machine.rateIn());
        addSection(sections, cards, false, baseY + inputH + FactoryLayout.IO_SECTION_GAP,
                contentX, innerW, output, machine.outputs().size(), machine.rateOut());
        return new Detail(panelX, topY, panelW, height, contentH, maxScroll, scroll,
                sections, cards);
    }

    private static void addSection(List<Section> sections, List<Card> cards, boolean input,
            int sectionTop, int contentX, int innerW, List<Placement> placements, int rules,
            long rate) {
        sections.add(new Section(input, sectionTop, rules, rate));
        int cardTop = sectionTop + FactoryLayout.IO_SECTION_H + GAP;
        for (Placement placement : placements) {
            cards.add(new Card(contentX + placement.x(), cardTop + placement.rowY(),
                    placement.width(), input, placement.ruleIndex(), placement.empty()));
        }
    }

    /**
     * 排一段：短名字两列，长名字整行；最后追加一张空卡。
     */
    private static List<Placement> place(FactoryLayout layout, List<ResourceSpec> rules,
            Font font) {
        int innerW = layout.ioInnerWidth();
        int cardW = layout.ioCellWidth();
        if (innerW <= 0 || cardW <= 0) {
            return List.of();
        }
        List<Placement> result = new ArrayList<>(rules.size() + 1);
        int rowY = 0;
        int used = 0;
        for (int i = 0; i < rules.size(); i++) {
            ResourceSpec spec = rules.get(i);
            int width = needsFullRow(font, spec, cardW) ? innerW : cardW;
            if (used > 0 && (width >= innerW || used + GAP + width > innerW)) {
                rowY += FactoryLayout.IO_CARD_H + GAP;
                used = 0;
            }
            int cellX = used == 0 ? 0 : used + GAP;
            result.add(new Placement(cellX, rowY, width, i, false));
            used = used == 0 ? width : used + GAP + width;
            if (width >= innerW || used >= innerW) {
                rowY += FactoryLayout.IO_CARD_H + GAP;
                used = 0;
            }
        }
        if (used > 0 && used + GAP + cardW > innerW) {
            rowY += FactoryLayout.IO_CARD_H + GAP;
            used = 0;
        }
        int cellX = used == 0 ? 0 : used + GAP;
        result.add(new Placement(cellX, rowY, cardW, rules.size(), true));
        return result;
    }

    private static boolean needsFullRow(Font font, ResourceSpec spec, int cardW) {
        return font.width(FactoryLabels.name(spec.key())) + 76 > cardW;
    }

    private static int sectionHeight(List<Placement> placements) {
        if (placements.isEmpty()) {
            return FactoryLayout.IO_SECTION_H + GAP;
        }
        Placement last = placements.get(placements.size() - 1);
        return FactoryLayout.IO_SECTION_H + GAP + last.rowY() + FactoryLayout.IO_CARD_H;
    }

    /**
     * 鼠标命中的卡片；只认面板内部。
     *
     * @param detail 布局
     * @param mouseX 鼠标 x
     * @param mouseY 鼠标 y
     * @return 卡片；没命中为 {@code null}
     */
    @Nullable
    static Card cardAt(Detail detail, double mouseX, double mouseY) {
        if (mouseX < detail.panelX()
                || mouseX >= detail.panelX() + detail.panelW()
                || mouseY < detail.panelY()
                || mouseY >= detail.panelY() + detail.panelH()) {
            return null;
        }
        for (Card card : detail.cards()) {
            if (mouseX >= card.x() && mouseX < card.x() + card.width()
                    && mouseY >= card.y() && mouseY < card.y() + FactoryLayout.IO_CARD_H) {
                return card;
            }
        }
        return null;
    }
}
