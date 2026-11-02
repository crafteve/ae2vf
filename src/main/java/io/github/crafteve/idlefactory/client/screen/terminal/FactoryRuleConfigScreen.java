package io.github.crafteve.idlefactory.client.screen.terminal;

import java.util.UUID;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEKey;
import appeng.client.gui.Icon;

import io.github.crafteve.idlefactory.client.screen.FactoryLabels;
import io.github.crafteve.idlefactory.client.screen.FactoryNineSlice;
import io.github.crafteve.idlefactory.client.screen.FactoryResourceIconRender;
import io.github.crafteve.idlefactory.factory.io.IoSide;
import io.github.crafteve.idlefactory.factory.machine.MatchMode;
import io.github.crafteve.idlefactory.network.payload.FactoryRuleActionC2S;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 被动 IO 规则的独立配置界面。
 *
 * <p>照 AE2「选择数量」那种独立小窗做：AE2 的通用背景板、AE2 的凸起按钮，标题在左上、
 * 返回按钮在右上。面（任意 / 上 / 下 / 东 / 西 / 南 / 北）和匹配（精准 / 模糊）各占一行，
 * 当前选中的按钮用 AE2 的按下态贴图高亮；按钮文字用 AE2 的亮色无阴影字。</p>
 *
 * <p>确定时发 {@link FactoryRuleActionC2S.Action#UPDATE}，由服务端重校验并回推快照；取消、
 * ESC 或右上角返回都回到打开前的终端界面。</p>
 */
final class FactoryRuleConfigScreen extends Screen {
    /** 窗口宽度：7 个面按钮一行放得下。 */
    private static final int WIDTH = 230;
    /** 窗口高度。 */
    private static final int HEIGHT = 140;
    /** AE2 普通按钮贴图。 */
    private static final ResourceLocation BUTTON =
            ResourceLocation.fromNamespaceAndPath("ae2", "button");
    /** AE2 按下 / 悬停按钮贴图。 */
    private static final ResourceLocation BUTTON_ACTIVE =
            ResourceLocation.fromNamespaceAndPath("ae2", "button_highlighted");
    /** 标题颜色：AE2 面板上的深色字。 */
    private static final int TITLE_COLOR = 0xFF404040;
    /** 按钮文字颜色：AE2 按钮上的亮色字，不带阴影。 */
    private static final int BUTTON_TEXT = 0xFFF2F2F2;
    /** 物品名称颜色：纯黑、不带阴影。 */
    private static final int NAME_COLOR = 0xFF000000;
    /** 返回按钮边长。 */
    private static final int BACK_SIZE = 20;

    /** 打开配置前的终端界面；关闭时切回去。 */
    private final Screen parent;
    /** 终端菜单容器 id。 */
    private final int containerId;
    /** 规则所属机器。 */
    private final UUID machineId;
    /** 输入还是输出。 */
    private final IoSide side;
    /** 规则资源。 */
    private final AEKey key;
    /** 当前选中的面；{@code null} 表示任意面。 */
    @Nullable
    private Direction face;
    /** 当前匹配模式。 */
    private MatchMode mode;

    /** 窗口左上角 x。 */
    private int left;
    /** 窗口左上角 y。 */
    private int top;

    /**
     * @param containerId 终端菜单容器 id
     * @param machineId   规则所属机器
     * @param side        输入 / 输出
     * @param key         规则资源
     * @param face        当前面；{@code null} 表示任意面
     * @param mode        当前匹配模式
     * @param parent      打开前的终端界面
     */
    FactoryRuleConfigScreen(int containerId, UUID machineId, IoSide side, AEKey key,
            @Nullable Direction face, MatchMode mode, Screen parent) {
        super(Component.literal("IO配置"));
        this.containerId = containerId;
        this.machineId = machineId;
        this.side = side;
        this.key = key;
        this.face = face;
        this.mode = mode;
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.left = (this.width - WIDTH) / 2;
        this.top = (this.height - HEIGHT) / 2;

        this.addRenderableWidget(new BackButton(this.left + WIDTH - BACK_SIZE - 6,
                this.top + 5, button -> this.onClose()));

        // 面：任意在前，其余按上 / 下 / 东 / 西 / 南 / 北排成一行。
        Direction[] faces = { null, Direction.UP, Direction.DOWN, Direction.EAST,
                Direction.WEST, Direction.SOUTH, Direction.NORTH };
        String[] labels = { "任意", "上", "下", "东", "西", "南", "北" };
        for (int i = 0; i < faces.length; i++) {
            Direction option = faces[i];
            this.addRenderableWidget(new OptionButton(this.left + 6 + i * 29, this.top + 54,
                    27, 18, Component.literal(labels[i]),
                    () -> this.face == option,
                    button -> this.face = option));
        }

        this.addRenderableWidget(new OptionButton(this.left + 6, this.top + 78, 50, 18,
                Component.literal("精准"), () -> this.mode == MatchMode.EXACT,
                button -> this.mode = MatchMode.EXACT));
        this.addRenderableWidget(new OptionButton(this.left + 60, this.top + 78, 50, 18,
                Component.literal("模糊"), () -> this.mode == MatchMode.IGNORE_COMPONENTS,
                button -> this.mode = MatchMode.IGNORE_COMPONENTS));

        this.addRenderableWidget(new OptionButton(this.left + 6, this.top + HEIGHT - 26, 50, 18,
                Component.literal("取消"), () -> false, button -> this.onClose()));
        this.addRenderableWidget(new OptionButton(this.left + WIDTH - 56,
                this.top + HEIGHT - 26, 50, 18, Component.literal("确定"), () -> false,
                button -> this.apply()));
    }

    /**
     * 提交修改：发 UPDATE 包并回到终端。
     */
    private void apply() {
        PacketDistributor.sendToServer(new FactoryRuleActionC2S(this.containerId,
                FactoryRuleActionC2S.Action.UPDATE, this.machineId, this.side, this.key,
                this.face, this.mode));
        this.onClose();
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.drawString(this.font, "IO配置", this.left + 8, this.top + 9, TITLE_COLOR, false);
        FactoryResourceIconRender.draw(graphics, this.font, this.key, this.left + 8,
                this.top + 28);
        graphics.drawString(this.font, FactoryLabels.name(this.key), this.left + 28,
                this.top + 32, NAME_COLOR, false);
        graphics.drawString(this.font, "面", this.left + 8, this.top + 43, TITLE_COLOR, false);
        graphics.drawString(this.font, "匹配", this.left + 8, this.top + 67, TITLE_COLOR, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0x80000000);
        FactoryNineSlice.terminalPanel(graphics, this.left, this.top, WIDTH, HEIGHT);
    }

    /**
     * 用 AE2 按钮贴图画的选项按钮；选中时用按下态贴图，文字用 AE2 的亮色无阴影字。
     */
    private static final class OptionButton extends Button {
        /** 是否处于选中状态；每次渲染现查，点击后下一帧自动变色。 */
        private final Supplier<Boolean> selected;

        /**
         * @param x        左边界
         * @param y        上边界
         * @param width    宽度
         * @param height   高度
         * @param message  按钮文字
         * @param selected 选中状态查询
         * @param onPress  点击动作
         */
        OptionButton(int x, int y, int width, int height, Component message,
                Supplier<Boolean> selected, OnPress onPress) {
            super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
            this.selected = selected;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY,
                float partialTick) {
            boolean active = this.selected.get() || this.isHoveredOrFocused();
            graphics.blitSprite(active ? BUTTON_ACTIVE : BUTTON, this.getX(), this.getY(),
                    this.getWidth(), this.getHeight());
            var font = Minecraft.getInstance().font;
            graphics.drawString(font, this.getMessage(),
                    this.getX() + (this.getWidth() - font.width(this.getMessage())) / 2,
                    this.getY() + (this.getHeight() - 8) / 2, BUTTON_TEXT, false);
        }
    }

    /**
     * 右上角的返回按钮：AE2 的深色工具栏按钮底 + 返回箭头。
     */
    private static final class BackButton extends Button {
        /**
         * @param x       左边界
         * @param y       上边界
         * @param onPress 点击动作
         */
        BackButton(int x, int y, OnPress onPress) {
            super(x, y, BACK_SIZE, BACK_SIZE, Component.empty(), onPress, DEFAULT_NARRATION);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY,
                float partialTick) {
            drawIcon(graphics, Icon.TOOLBAR_BUTTON_BACKGROUND, this.getX() + 2, this.getY() + 2);
            drawIcon(graphics, Icon.BACK, this.getX() + 2, this.getY() + 2);
        }

        private static void drawIcon(GuiGraphics graphics, Icon icon, int x, int y) {
            graphics.blit(Icon.TEXTURE, x, y, icon.width, icon.height,
                    icon.x, icon.y, icon.width, icon.height,
                    Icon.TEXTURE_WIDTH, Icon.TEXTURE_HEIGHT);
        }
    }
}
