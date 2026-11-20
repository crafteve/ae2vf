package io.github.crafteve.idlefactory.client.screen.sort;


import io.github.crafteve.idlefactory.client.screen.theme.GuiTextures;

import java.util.List;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import appeng.client.gui.Icon;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.widgets.IconButton;

import io.github.crafteve.idlefactory.menu.TerminalSortMode;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 档位按钮：图标随当前档位变化，点击按 名称 → ID → 机器台数 → IO 规则数 → 状态 → 距离 循环。
 *
 * <p>AE2 的三个档位直接用 AE2 的 {@link Icon}；另外三档是我们自绘的 16×16 贴图，所以按钮不能
 * 只靠 {@code getIcon()}，必须重写绘制：背景照抄 AE2 工具栏按钮的规则（普通 / 悬停 / 聚焦三种
 * 底图 + 同样的偏移），图标槽位也沿用 AE2 的 {@code (x, y+1+悬停)}，这样自绘图标和 AE2 原生按钮
 * 摆在一起看不出差别。</p>
 */
public final class SortModeButton extends IconButton {
    /** 当前档位来源；按钮每帧现查，切档后下一帧自动换图标。 */
    private final Supplier<TerminalSortMode> mode;
    private final SortPress press;

    /**
     * @param mode  当前档位
     * @param press 点击动作
     */
    public SortModeButton(Supplier<TerminalSortMode> mode, SortPress press) {
        super(button -> { });
        this.mode = mode;
        this.press = press;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 左键正向、右键反向：只处理右键会让左键点击落到空的 onPress 上，表现为按钮点不动。
        if ((button == 0 || button == 1) && this.active && this.visible
                && clicked(mouseX, mouseY)) {
            this.press.press(button == 1);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected Icon getIcon() {
        Icon icon = iconFor(this.mode.get());
        // 自绘档位没有 Icon；这里只在 AE2 内部错误路径上被读到，给一个排序图标兜底。
        return icon == null ? Icon.SORT_BY_NAME : icon;
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!this.visible) {
            return;
        }
        TerminalSortMode current = this.mode.get();
        boolean hovered = this.isHovered();
        if (!this.isDisableBackground()) {
            Icon background = hovered ? Icon.TOOLBAR_BUTTON_BACKGROUND_HOVER
                    : this.isFocused() ? Icon.TOOLBAR_BUTTON_BACKGROUND_FOCUS
                            : Icon.TOOLBAR_BUTTON_BACKGROUND;
            background.getBlitter()
                    .dest(this.getX() - 1, this.getY() + (hovered ? 1 : 0), 18, 20)
                    .zOffset(2)
                    .blit(graphics);
        }
        Blitter icon = iconBlitter(current);
        if (icon == null) {
            return;
        }
        if (!this.active) {
            icon.opacity(0.5F);
        }
        icon.dest(this.getX(), this.getY() + 1 + (hovered ? 1 : 0),
                        GuiTextures.SORT_ICON_SIZE, GuiTextures.SORT_ICON_SIZE)
                .zOffset(3)
                .blit(graphics);
    }

    @Override
    public List<Component> getTooltipMessage() {
        TerminalSortMode current = this.mode.get();
        return List.of(Component.translatable("idlefactory.sort.mode.current", label(current)),
                Component.translatable("idlefactory.sort.mode.next", label(current.next())));
    }

    /**
     * 取档位的图标绘制器：AE2 档位用原生图标，自绘档位用项目自己的贴图。
     *
     * <p>只配数据源不配目标位置，目标位置由绘制时统一填，保证两种图标落在同一个 16×16 槽位。</p>
     *
     * @param mode 档位
     * @return 绘制器；没有对应贴图时 {@code null}
     */
    @Nullable
    private static Blitter iconBlitter(TerminalSortMode mode) {
        Icon icon = iconFor(mode);
        if (icon != null) {
            return icon.getBlitter();
        }
        ResourceLocation texture = textureFor(mode);
        if (texture == null) {
            return null;
        }
        return Blitter.texture(texture, GuiTextures.SORT_ICON_SIZE,
                        GuiTextures.SORT_ICON_SIZE)
                .src(0, 0, GuiTextures.SORT_ICON_SIZE, GuiTextures.SORT_ICON_SIZE);
    }

    /**
     * AE2 自带的档位图标；自绘档位返回 {@code null}。
     *
     * @param mode 档位
     * @return AE2 图标或 {@code null}
     */
    @Nullable
    private static Icon iconFor(TerminalSortMode mode) {
        return switch (mode) {
            case NAME -> Icon.SORT_BY_NAME;
            case ID -> Icon.SORT_BY_MOD;
            case MACHINE_COUNT -> Icon.SORT_BY_AMOUNT;
            case RULE_COUNT, STATUS, DISTANCE -> null;
        };
    }

    /**
     * 自绘档位的贴图；AE2 档位返回 {@code null}。
     *
     * @param mode 档位
     * @return 贴图或 {@code null}
     */
    @Nullable
    private static ResourceLocation textureFor(TerminalSortMode mode) {
        return switch (mode) {
            case RULE_COUNT -> GuiTextures.SORT_RULE_COUNT;
            case STATUS -> GuiTextures.SORT_STATUS;
            case DISTANCE -> GuiTextures.SORT_DISTANCE;
            case NAME, ID, MACHINE_COUNT -> null;
        };
    }

    /**
     * 档位的中文名，给 tooltip 用。
     *
     * @param mode 档位
     * @return 中文名
     */
    private static String tr(String key) {
        return net.minecraft.network.chat.Component.translatable(key).getString();
    }

    private static String label(TerminalSortMode mode) {
        return switch (mode) {
            case NAME -> tr("idlefactory.sort.mode.name");
            case ID -> "ID";
            case MACHINE_COUNT -> tr("idlefactory.sort.mode.machines");
            case RULE_COUNT -> tr("idlefactory.sort.mode.rules");
            case STATUS -> tr("idlefactory.sort.mode.status");
            case DISTANCE -> tr("idlefactory.sort.mode.distance");
        };
    }
}
