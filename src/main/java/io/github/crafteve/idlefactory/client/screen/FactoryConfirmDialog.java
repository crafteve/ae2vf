package io.github.crafteve.idlefactory.client.screen;

import io.github.crafteve.idlefactory.client.screen.NineSliceRenderer;


import io.github.crafteve.idlefactory.client.screen.theme.GuiLayerSpec;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 破坏性操作的确认浮层（删除机器 / 删除节点）。
 *
 * <p>不带输入框；确认后立即关闭并执行回调，结果由服务端反馈显示。Esc / 取消关闭，回车确认。
 * 浮层外点击一律吞掉，避免误触背后的列表。</p>
 *
 * <p>几何分两部分：{@link Bounds} 是浮层自身的位置，{@link #backdrop()} 是要压暗的父面板。
 * 两者都由调用方（节点终端）直接给出屏幕矩形。</p>
 *
 * <p>绘制层级统一走 {@link GuiLayerSpec#OVERLAY}：普通填充在 z=0，而物品图标在 z=150、
 * 数量装饰在 z=200，深度测试按 z 比较、不看绘制顺序，不抬层就会被列表里的图标盖住。</p>
 */
public final class FactoryConfirmDialog {
    /** 浮层尺寸。 */
    private static final int WIDTH = 240;
    private static final int MIN_WIDTH = 160;
    private static final int HEIGHT = 84;
    /** 说明文字行高。 */
    private static final int LINE_HEIGHT = 11;

    /**
     * 一个矩形。
     *
     * @param x      左边缘
     * @param y      上边缘
     * @param width  宽
     * @param height 高
     */
    public record Bounds(int x, int y, int width, int height) {
    }

    private final Font font;
    /** 面板底色（AE2 终端内部实色）。 */
    private static final int PANEL_BG = 0xFFCBCCD4;
    /** 正文色。 */
    private static final int TEXT_PRIMARY = 0xFF000000;
    /** 次要文字。 */
    private static final int TEXT_DIM = 0xFF5F5F5F;
    /** 危险动作色。 */
    private static final int DANGER = 0xFFD63A3A;

    private boolean open;
    private String title = "";
    /** 已经按宽度折好的说明行；打开时算一次，绘制与测量共用。 */
    private List<String> lines = List.of();
    private String confirmLabel = tr("idlefactory.common.confirm");
    @Nullable
    private Runnable onConfirm;
    @Nullable
    private Bounds dialog;
    @Nullable
    private Bounds backdrop;

    /**
     * @param font  字体
     * @param theme 主题
     */
    public FactoryConfirmDialog(Font font) {
        this.font = font;
    }

    /**
     * 打开确认浮层。
     *
     * <p>浮层尺寸由说明文字算出来：先按可用宽度折行，再按行数定高度，最后在
     * {@code area} 里居中。调用方只给「可用区域」与「要压暗的区域」，不再手写宽高。</p>
     *
     * @param title        标题
     * @param message      说明；可用 {@code \n} 分行
     * @param confirmLabel 确认按钮文字
     * @param onConfirm    确认回调
     * @param area         浮层可用的屏幕区域（通常是终端窗口）
     * @param backdrop     要压暗的屏幕区域（通常是整个屏幕）
     */
    public void open(String title, String message, String confirmLabel, Runnable onConfirm,
            Bounds area, Bounds backdrop) {
        this.title = title;
        this.confirmLabel = confirmLabel;
        this.onConfirm = onConfirm;
        this.backdrop = backdrop;

        int width = Math.max(MIN_WIDTH, Math.min(WIDTH, area.width() - 12));
        int textWidth = width - 24;
        List<String> wrapped = wrapMessage(message, textWidth);
        int contentHeight = 28 + wrapped.size() * LINE_HEIGHT + 28;
        int height = Math.max(HEIGHT, Math.min(contentHeight, area.height() - 12));
        // 区域太矮时只画放得下的行，末行加省略号，绝不让文字压到按钮上。
        // 可用文字高度 = height - 上边 28 - 按钮行 26，再按行高向下取整。
        int maxLines = Math.max(1, (height - 47) / LINE_HEIGHT);
        if (wrapped.size() > maxLines) {
            List<String> limited = new ArrayList<>(wrapped.subList(0, maxLines));
            limited.set(maxLines - 1, limited.get(maxLines - 1) + "…");
            wrapped = limited;
        }
        this.lines = List.copyOf(wrapped);
        this.dialog = new Bounds(area.x() + (area.width() - width) / 2,
                area.y() + (area.height() - height) / 2, width, height);
        this.open = true;
    }

    /** 关闭。 */
    public void close() {
        this.open = false;
        this.onConfirm = null;
    }

    /** 是否打开。 */
    public boolean isOpen() {
        return this.open;
    }

    /**
     * 画浮层。
     *
     * @param graphics 画布
     * @param mouseX   鼠标 x
     * @param mouseY   鼠标 y
     */
    public void draw(GuiGraphics graphics, int mouseX, int mouseY) {
        Bounds box = this.dialog;
        Bounds parent = this.backdrop;
        if (!this.open || box == null || parent == null) {
            return;
        }
        // 先把已有批次落屏（物品图标是延迟批处理的），再整体抬 z 画浮层。
        graphics.flush();
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, GuiLayerSpec.OVERLAY.z());
        graphics.fill(parent.x(), parent.y(), parent.x() + parent.width(),
                parent.y() + parent.height(), 0x90000000);
        // 浮层内区必须先铺一块完全不透明的底：用半透明分隔线贴图会让背后的机器图标透上来，
        // 看起来像浮层被压在内容下面（z 值问题），实际是底色没盖住。
        graphics.fill(box.x() + 1, box.y() + 1, box.x() + box.width() - 1,
                box.y() + box.height() - 1, PANEL_BG);
        NineSliceRenderer.panel(graphics, box.x(), box.y(), box.width(), box.height());
        graphics.drawString(this.font, this.title, box.x() + 12, box.y() + 10,
                TEXT_PRIMARY, false);

        int lineY = box.y() + 28;
        for (String line : this.lines) {
            graphics.drawString(this.font, line, box.x() + 12, lineY, TEXT_DIM,
                    false);
            lineY += LINE_HEIGHT;
        }

        int buttonY = box.y() + box.height() - 26;
        int confirmW = Math.max(48, this.font.width(this.confirmLabel) + 18);
        String cancelLabel = tr("idlefactory.common.cancel");
        int cancelW = Math.max(40, this.font.width(cancelLabel) + 18);
        int confirmX = box.x() + box.width() - 12 - confirmW;
        int cancelX = confirmX - 6 - cancelW;
        drawButton(graphics, cancelX, buttonY, cancelW, cancelLabel, false, mouseX, mouseY);
        drawButton(graphics, confirmX, buttonY, confirmW, this.confirmLabel, true,
                mouseX, mouseY);
        graphics.pose().popPose();
    }

    private static String tr(String key) {
        return net.minecraft.network.chat.Component.translatable(key).getString();
    }

    private void drawButton(GuiGraphics graphics, int x, int y, int w, String label,
            boolean danger, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 18;
        if (hovered) {
            NineSliceRenderer.buttonHover(graphics, x, y, w, 18);
        } else {
            NineSliceRenderer.button(graphics, x, y, w, 18);
        }
        int color = danger ? DANGER : TEXT_PRIMARY;
        graphics.drawString(this.font, label,
                x + (w - this.font.width(label)) / 2, y + 5, color, false);
    }

    /**
     * 处理点击。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @param button 鼠标键
     * @return 是否消费
     */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        Bounds box = this.dialog;
        if (!this.open || box == null) {
            return false;
        }
        if (!inside(mouseX, mouseY, box.x(), box.y(), box.width(), box.height())) {
            return true;
        }
        int buttonY = box.y() + box.height() - 26;
        int confirmW = Math.max(48, this.font.width(this.confirmLabel) + 18);
        int cancelW = Math.max(40, this.font.width("取消") + 18);
        int confirmX = box.x() + box.width() - 12 - confirmW;
        int cancelX = confirmX - 6 - cancelW;
        if (inside(mouseX, mouseY, cancelX, buttonY, cancelW, 18)) {
            close();
            return true;
        }
        if (inside(mouseX, mouseY, confirmX, buttonY, confirmW, 18)) {
            Runnable action = this.onConfirm;
            close();
            if (action != null) {
                action.run();
            }
            return true;
        }
        return true;
    }

    /**
     * 处理键盘。
     *
     * @param keyCode 键码
     * @return 是否消费
     */
    public boolean keyPressed(int keyCode) {
        if (!this.open) {
            return false;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
            Runnable action = this.onConfirm;
            close();
            if (action != null) {
                action.run();
            }
            return true;
        }
        return true;
    }

    /**
     * 按像素宽度折行：窄终端里说明文字不能硬截断。
     *
     * @param text  原文
     * @param width 可用宽度
     * @return 折好的行
     */
    /**
     * 把整段说明（可能带 {@code \n}）折成行。
     *
     * @param message 原文
     * @param width   可用宽度
     * @return 折好的行
     */
    private List<String> wrapMessage(String message, int width) {
        List<String> result = new ArrayList<>();
        for (String raw : message.split("\n")) {
            result.addAll(wrap(raw, width));
        }
        return result;
    }

    private List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        String rest = text;
        while (!rest.isEmpty() && width > 0) {
            String head = this.font.plainSubstrByWidth(rest, width);
            if (head.isEmpty()) {
                break;
            }
            lines.add(head);
            rest = rest.substring(head.length());
        }
        return lines;
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width,
            int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
}
