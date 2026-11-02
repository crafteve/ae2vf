package io.github.crafteve.idlefactory.client.screen.terminal;

import java.util.UUID;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEKey;

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
 * <p>照 AE2「选择数量」那种独立小窗做：浅色面板 + 凸起按钮。面（任意 / 上 / 下 / 东 / 西 /
 * 南 / 北）和匹配（精准 / 模糊）各占一行，当前选中的按钮用 AE2 的按下态贴图高亮；左上角
 * 显示规则摘要（资源图标 + 名称），不显示删除——删除在面板里右键点一下就行。</p>
 *
 * <p>确定时发 {@link FactoryRuleActionC2S.Action#UPDATE}，由服务端重校验并回推快照；取消或
 * ESC 直接回到打开前的终端界面。</p>
 */
final class FactoryRuleConfigScreen extends Screen {
    /** 窗口宽度：7 个面按钮一行放得下。 */
    private static final int WIDTH = 220;
    /** 窗口高度。 */
    private static final int HEIGHT = 150;
    /** AE2 普通按钮贴图。 */
    private static final ResourceLocation BUTTON =
            ResourceLocation.fromNamespaceAndPath("ae2", "button");
    /** AE2 按下 / 悬停按钮贴图。 */
    private static final ResourceLocation BUTTON_ACTIVE =
            ResourceLocation.fromNamespaceAndPath("ae2", "button_highlighted");
    /** 正文颜色。 */
    private static final int TEXT_COLOR = 0xFF404040;
    /** 次要文字颜色。 */
    private static final int TEXT_MUTED = 0xFF707070;

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
        super(Component.literal("配置规则"));
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

        this.addRenderableWidget(new OptionButton(this.left + 6, this.top + 98, 50, 18,
                Component.literal("精准"), () -> this.mode == MatchMode.EXACT,
                button -> this.mode = MatchMode.EXACT));
        this.addRenderableWidget(new OptionButton(this.left + 60, this.top + 98, 50, 18,
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
        graphics.drawString(this.font, "配置规则", this.left + 8, this.top + 8, TEXT_COLOR, false);
        FactoryResourceIconRender.draw(graphics, this.font, this.key, this.left + 8,
                this.top + 28);
        graphics.drawString(this.font, FactoryLabels.name(this.key), this.left + 28,
                this.top + 32, TEXT_COLOR, false);
        graphics.drawString(this.font, "面", this.left + 8, this.top + 42, TEXT_MUTED, false);
        graphics.drawString(this.font, "匹配", this.left + 8, this.top + 86, TEXT_MUTED, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0x80000000);
        FactoryNineSlice.panel(graphics, this.left, this.top, WIDTH, HEIGHT);
    }

    /**
     * 用 AE2 按钮贴图画的选项按钮；选中时用按下态贴图。
     */
    private static final class OptionButton extends Button {
        /** 是否处于选中状态；每次渲染现查，点击后下一帧自动变色。 */
        private final Supplier<Boolean> selected;

        /**
         * @param x       左边界
         * @param y       上边界
         * @param width   宽度
         * @param height  高度
         * @param message 按钮文字
         * @param selected 选中状态查询
         * @param onPress 点击动作
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
            graphics.drawCenteredString(Minecraft.getInstance().font, this.getMessage(),
                    this.getX() + this.getWidth() / 2,
                    this.getY() + (this.getHeight() - 8) / 2, TEXT_COLOR);
        }
    }
}
