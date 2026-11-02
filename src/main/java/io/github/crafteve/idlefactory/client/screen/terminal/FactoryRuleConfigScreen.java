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
 * 返回按钮在右上。只有三行内容——物品图标与名称、面（任意 / 上 / 下 / 东 / 西 / 南 / 北）、
 * 匹配（精准 / 模糊）。当前选中的按钮用 AE2 的按下态贴图高亮，按钮文字用 AE2 的亮色无阴影字。</p>
 *
 * <p>没有确定 / 取消：点选即改，右上角返回、取消或 ESC 都回到终端界面；确定时发
 * {@link FactoryRuleActionC2S.Action#UPDATE}，由服务端重校验并回推快照。</p>
 */
final class FactoryRuleConfigScreen extends Screen {
    /** 窗口宽度：7 个面按钮一行放得下。 */
    private static final int WIDTH = 230;
    /** 窗口高度：标题栏 + 物品行 + 面行 + 匹配行。 */
    private static final int HEIGHT = 96;
    /** 物品槽位边长：图标 16×16 居中托在槽里。 */
    private static final int ITEM_SLOT = 20;
    /** 面按钮行总宽：7 个 27px 按钮 + 6 个 2px 间隔。 */
    private static final int FACE_ROW_WIDTH = 7 * 27 + 6 * 2;
    /** 匹配按钮行总宽：2 个 50px 按钮 + 2px 间隔。 */
    private static final int MODE_ROW_WIDTH = 2 * 50 + 2;
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
    private static final int BACK_SIZE = 24;
    /** 返回箭头染色：AE2 的 BACK 是白箭头，图 1 的按钮上是深色箭头。 */
    private static final float BACK_TINT = 0.25F;

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

        // 返回按钮骑在面板右上角的边框上：右边缘与面板右边缘平齐，顶部略高出边框。
        this.addRenderableWidget(new BackButton(this.left + WIDTH - BACK_SIZE,
                this.top - 5, button -> this.onClose()));

        // 面：一行居中，任意在前，其余按上 / 下 / 东 / 西 / 南 / 北；点一下即生效。
        int faceX = this.left + (WIDTH - FACE_ROW_WIDTH) / 2;
        Direction[] faces = { null, Direction.UP, Direction.DOWN, Direction.EAST,
                Direction.WEST, Direction.SOUTH, Direction.NORTH };
        String[] labels = { "任意", "上", "下", "东", "西", "南", "北" };
        for (int i = 0; i < faces.length; i++) {
            Direction option = faces[i];
            this.addRenderableWidget(new OptionButton(faceX + i * 29, this.top + 50,
                    27, 18, Component.literal(labels[i]),
                    () -> this.face == option,
                    button -> {
                        this.face = option;
                        this.update();
                    }));
        }

        int modeX = this.left + (WIDTH - MODE_ROW_WIDTH) / 2;
        this.addRenderableWidget(new OptionButton(modeX, this.top + 74, 50, 18,
                Component.literal("精准"), () -> this.mode == MatchMode.EXACT,
                button -> {
                    this.mode = MatchMode.EXACT;
                    this.update();
                }));
        this.addRenderableWidget(new OptionButton(modeX + 52, this.top + 74, 50, 18,
                Component.literal("模糊"), () -> this.mode == MatchMode.IGNORE_COMPONENTS,
                button -> {
                    this.mode = MatchMode.IGNORE_COMPONENTS;
                    this.update();
                }));
    }

    /**
     * 点选即生效：把当前面与匹配模式发给服务端，不等关闭。
     */
    private void update() {
        PacketDistributor.sendToServer(new FactoryRuleActionC2S(this.containerId,
                FactoryRuleActionC2S.Action.UPDATE, this.machineId, this.side, this.key,
                this.face, this.mode));
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Screen.render 内部会先 renderBackground（画面板）再画按钮，所以文字必须最后画，
        // 否则会被背景盖掉。
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(this.font, "IO配置", this.left + 8, this.top + 7, TITLE_COLOR, false);
        // 物品图标托在槽位里，不是凭空画的孤块。
        FactoryNineSlice.slot(graphics, this.left + 8, this.top + 26, ITEM_SLOT, ITEM_SLOT);
        FactoryResourceIconRender.draw(graphics, this.font, this.key, this.left + 10,
                this.top + 28);
        graphics.drawString(this.font, FactoryLabels.name(this.key), this.left + 32,
                this.top + 30, NAME_COLOR, false);
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
     * 右上角的返回按钮：AE2 普通按钮底 + 染成深色的返回箭头。
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
            graphics.blitSprite(this.isHoveredOrFocused() ? BUTTON_ACTIVE : BUTTON,
                    this.getX(), this.getY(), this.getWidth(), this.getHeight());
            int iconSize = Icon.BACK.width;
            int iconX = this.getX() + (this.getWidth() - iconSize) / 2;
            int iconY = this.getY() + (this.getHeight() - Icon.BACK.height) / 2;
            graphics.setColor(BACK_TINT, BACK_TINT, BACK_TINT, 1.0F);
            graphics.blit(Icon.TEXTURE, iconX, iconY, Icon.BACK.width, Icon.BACK.height,
                    Icon.BACK.x, Icon.BACK.y, Icon.BACK.width, Icon.BACK.height,
                    Icon.TEXTURE_WIDTH, Icon.TEXTURE_HEIGHT);
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }
}
