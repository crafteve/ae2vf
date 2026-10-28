package io.github.crafteve.idlefactory.client.screen;

import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.client.theme.GuiTheme;
import io.github.crafteve.idlefactory.client.widget.GuiTextures;
import io.github.crafteve.idlefactory.client.widget.NineSlice;
import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder;
import io.github.crafteve.idlefactory.network.payload.FactoryEditFeedbackS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryLineActionC2S;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 工作台编辑态的总控：产线下拉、命名/确认浮层、底部反馈。
 *
 * <p>它把界面动作翻译成 C2S 包，并且只根据服务端反馈与下一份快照更新界面；不做乐观写入。
 * 绘制与命中仍由各组件自己负责，本类只管顺序和状态。</p>
 *
 * <p>输入优先级：命名浮层 → 确认浮层 → 产线下拉 → 交给 {@link FactoryScreen} 的原有逻辑。</p>
 */
public final class FactoryEditController {
    /** 底部反馈停留 tick。 */
    private static final int FEEDBACK_TICKS = 80;

    private final FactoryScreen screen;
    private final Font font;
    private final GuiTheme theme;
    private final LineSelectorMenu lineMenu;
    private final LineNameDialog nameDialog;
    private final ConfirmDialog confirmDialog;

    @Nullable
    private FactoryLayout layout;
    @Nullable
    private String feedbackText;
    private boolean feedbackOk;
    private int feedbackTicks;
    /** 正在等待命名浮层的回包。 */
    private boolean awaitingName;
    /** 发给服务端后等待回包的动作；只用于选中/清空选中和错误分流。 */
    @Nullable
    private FactoryLineActionC2S.Action pendingAction;
    @Nullable
    private UUID pendingLineId;
    @Nullable
    private UUID renameLineId;

    /**
     * @param screen 工作台
     * @param font   字体
     * @param theme  主题
     */
    public FactoryEditController(FactoryScreen screen, Font font, GuiTheme theme) {
        this.screen = screen;
        this.font = font;
        this.theme = theme;
        this.lineMenu = new LineSelectorMenu(new LineSelectorMenu.Callbacks() {
            @Override
            public void select(@Nullable UUID lineId) {
                screen.selectLine(lineId);
            }

            @Override
            public void create() {
                openNameDialog(LineNameDialog.Mode.CREATE, null, null);
            }

            @Override
            public void rename(FactorySnapshotBuilder.LineView line) {
                openNameDialog(LineNameDialog.Mode.RENAME, line.name(), line.id());
            }

            @Override
            public void delete(FactorySnapshotBuilder.LineView line) {
                confirmDelete(line);
            }

            @Override
            public void setRunning(FactorySnapshotBuilder.LineView line, boolean running) {
                sendLineAction(running ? FactoryLineActionC2S.Action.START_LINE
                        : FactoryLineActionC2S.Action.PAUSE_LINE, line.id(), null, null);
            }
        });
        this.nameDialog = new LineNameDialog(font, theme, this::confirmName);
        this.confirmDialog = new ConfirmDialog(font, theme);
    }

    /**
     * 布局变化时重新绑定。
     *
     * @param layout 新布局
     */
    public void rebind(FactoryLayout layout) {
        this.layout = layout;
        this.nameDialog.rebind(layout);
        this.confirmDialog.rebind(layout);
    }

    /**
     * 快照更新后刷新菜单数据。
     *
     * @param lines           产线列表
     * @param selectedLineId  当前选中
     * @param hasFactory      是否有工厂
     */
    public void onTable(List<FactorySnapshotBuilder.LineView> lines,
            @Nullable UUID selectedLineId, boolean hasFactory) {
        this.lineMenu.update(lines, selectedLineId, hasFactory);
        if (!hasFactory) {
            this.nameDialog.close();
            this.confirmDialog.close();
        }
    }

    /**
     * 打开/收起产线下拉。
     *
     * @param anchorX 下拉左边缘
     * @param anchorY 下拉上边缘
     */
    public void toggleLineMenu(int anchorX, int anchorY) {
        if (this.lineMenu.isOpen()) {
            this.lineMenu.close();
            return;
        }
        if (this.layout == null) {
            return;
        }
        this.lineMenu.open(this.layout, anchorX, anchorY, this.screen.lines(),
                this.screen.selectedLineId(), this.screen.hasFactory());
    }

    /** 关闭下拉与浮层。 */
    public void closeAll() {
        this.lineMenu.close();
        this.nameDialog.close();
        this.confirmDialog.close();
        this.awaitingName = false;
        this.pendingAction = null;
        this.pendingLineId = null;
    }

    /** 产线下拉是否打开。 */
    public boolean isLineMenuOpen() {
        return this.lineMenu.isOpen();
    }

    /** 是否有关闭优先的浮层/下拉打开，供 tooltip 与 JEI 判断遮挡。 */
    public boolean isMenuOpen() {
        return this.lineMenu.isOpen() || this.nameDialog.isOpen() || this.confirmDialog.isOpen();
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
        if (this.nameDialog.isOpen()) {
            return this.nameDialog.mouseClicked(mouseX, mouseY, button);
        }
        if (this.confirmDialog.isOpen()) {
            return this.confirmDialog.mouseClicked(mouseX, mouseY, button);
        }
        if (this.lineMenu.isOpen()) {
            return this.lineMenu.mouseClicked(mouseX, mouseY, button);
        }
        return false;
    }

    /**
     * 处理键盘。
     *
     * @param keyCode   键码
     * @param scanCode  扫描码
     * @param modifiers 修饰键
     * @return 是否消费
     */
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.nameDialog.isOpen()) {
            return this.nameDialog.keyPressed(keyCode, scanCode, modifiers);
        }
        if (this.confirmDialog.isOpen()) {
            return this.confirmDialog.keyPressed(keyCode);
        }
        if (this.lineMenu.isOpen()
                && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            this.lineMenu.close();
            return true;
        }
        return false;
    }

    /**
     * 处理字符输入。
     *
     * @param codePoint 字符
     * @param modifiers 修饰键
     * @return 是否消费
     */
    public boolean charTyped(char codePoint, int modifiers) {
        return this.nameDialog.isOpen() && this.nameDialog.charTyped(codePoint, modifiers);
    }

    /**
     * 处理滚轮。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @param delta  滚动量
     * @return 是否消费
     */
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (this.nameDialog.isOpen() || this.confirmDialog.isOpen()) {
            return true;
        }
        if (this.lineMenu.isOpen()) {
            this.lineMenu.mouseScrolled(mouseX, mouseY, delta);
            return true;
        }
        return false;
    }

    /** 输入框光标与反馈计时。 */
    public void tick() {
        this.nameDialog.tick();
        if (this.feedbackTicks > 0) {
            this.feedbackTicks--;
        }
    }

    /**
     * 画下拉、浮层和反馈；必须在表格/右区之后调用。
     *
     * @param graphics    画布
     * @param mouseX      鼠标 x
     * @param mouseY      鼠标 y
     * @param partialTick 渲染插值
     */
    public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (this.layout == null) {
            return;
        }
        this.lineMenu.draw(graphics, this.font, this.theme, mouseX, mouseY);
        drawFeedback(graphics);
        this.nameDialog.draw(graphics, mouseX, mouseY, partialTick);
        this.confirmDialog.draw(graphics, mouseX, mouseY);
    }

    /**
     * 处理服务端反馈。
     *
     * @param payload 反馈
     */
    public void onFeedback(FactoryEditFeedbackS2C payload) {
        FactoryLineActionC2S.Action action = this.pendingAction;
        UUID lineId = this.pendingLineId;
        this.pendingAction = null;
        this.pendingLineId = null;

        if (this.awaitingName) {
            this.awaitingName = false;
            if (payload.ok()) {
                this.nameDialog.close();
            } else {
                this.nameDialog.setError(payload.message());
                return;
            }
        }

        if (payload.ok() && action != null) {
            if (action == FactoryLineActionC2S.Action.CREATE_LINE
                    || action == FactoryLineActionC2S.Action.RENAME_LINE) {
                if (payload.lineId() != null) {
                    this.screen.selectLine(payload.lineId());
                }
            } else if (action == FactoryLineActionC2S.Action.DELETE_LINE
                    && lineId != null && lineId.equals(this.screen.selectedLineId())) {
                this.screen.selectLine(null);
            }
        }

        this.feedbackText = payload.message();
        this.feedbackOk = payload.ok();
        this.feedbackTicks = FEEDBACK_TICKS;
    }

    private void openNameDialog(LineNameDialog.Mode mode, @Nullable String initial,
            @Nullable UUID lineId) {
        if (this.layout == null) {
            return;
        }
        this.renameLineId = lineId;
        this.nameDialog.open(mode, initial, this.layout);
    }

    private void confirmName(LineNameDialog.Mode mode, String name) {
        if (mode == LineNameDialog.Mode.CREATE) {
            this.pendingAction = FactoryLineActionC2S.Action.CREATE_LINE;
            this.pendingLineId = null;
            this.awaitingName = true;
            sendLineAction(FactoryLineActionC2S.Action.CREATE_LINE, null, null, name);
        } else {
            if (this.renameLineId == null) {
                this.nameDialog.setError("找不到要重命名的产线");
                return;
            }
            this.pendingAction = FactoryLineActionC2S.Action.RENAME_LINE;
            this.pendingLineId = this.renameLineId;
            this.awaitingName = true;
            sendLineAction(FactoryLineActionC2S.Action.RENAME_LINE, this.renameLineId, null,
                    name);
        }
    }

    private void confirmDelete(FactorySnapshotBuilder.LineView line) {
        if (this.layout == null) {
            return;
        }
        this.confirmDialog.open("删除产线",
                "删除产线 [" + line.name() + "]？\n机器会失去引用，规则会一起移除。",
                "删除",
                () -> {
                    this.pendingAction = FactoryLineActionC2S.Action.DELETE_LINE;
                    this.pendingLineId = line.id();
                    sendLineAction(FactoryLineActionC2S.Action.DELETE_LINE, line.id(), null, null);
                },
                this.layout);
    }

    private void sendLineAction(FactoryLineActionC2S.Action action, @Nullable UUID lineId,
            @Nullable UUID machineId, @Nullable String name) {
        PacketDistributor.sendToServer(new FactoryLineActionC2S(
                this.screen.getMenu().containerId, action, lineId, machineId, name));
    }

    private void drawFeedback(GuiGraphics graphics) {
        if (this.feedbackText == null || this.feedbackTicks <= 0 || this.layout == null) {
            return;
        }
        FactoryLayout l = this.layout;
        NineSlice.fill(graphics, GuiTextures.HEADER_BG, l.tableX, l.footerY, l.tableW,
                FactoryLayout.FOOTER_H);
        String text = this.font.plainSubstrByWidth(this.feedbackText, l.tableW - 4);
        graphics.drawString(this.font, text, l.tableX + 2, l.footerY + 3,
                this.feedbackOk ? this.theme.statusRun() : this.theme.error(), false);
    }
}
