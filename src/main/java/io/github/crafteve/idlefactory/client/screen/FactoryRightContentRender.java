package io.github.crafteve.idlefactory.client.screen;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 右区的动态内容：tab 文字、仓库/机器库切换和底部标签。
 *
 * <p>静态凹槽底板由 {@link FactoryChromeRender} 先铺好；这里只按当前 tab 分派到
 * 仓库格或机器库卡片，并画「合成 / 背包」标签。</p>
 */
final class FactoryRightContentRender {
    private final FactoryScreenContext context;
    private final FactoryStorageRender storageRender;
    private final FactoryMachineLibraryRender machineLibraryRender;

    /**
     * @param context 界面只读视图
     */
    FactoryRightContentRender(FactoryScreenContext context) {
        this.context = context;
        this.storageRender = new FactoryStorageRender(context);
        this.machineLibraryRender = new FactoryMachineLibraryRender(context);
    }

    /**
     * 画右区的动态内容。
     *
     * @param graphics 画布
     * @param mouseX   鼠标 x
     * @param mouseY   鼠标 y
     */
    void draw(GuiGraphics graphics, int mouseX, int mouseY) {
        FactoryLayout l = this.context.layout();
        String[] tabs = { "仓库", "机器" };
        int tabWidth = l.rightW / FactoryLayout.TAB_COUNT;
        for (int i = 0; i < tabs.length; i++) {
            int x = l.rightX + i * tabWidth;
            graphics.drawString(this.context.font(), tabs[i],
                    x + (tabWidth - this.context.font().width(tabs[i])) / 2,
                    l.tabY + (FactoryLayout.HEADER_H - 9) / 2,
                    this.context.theme().textPrimary(),
                    false);
        }

        if (this.context.state().activeTab == FactoryScreen.TAB_STORAGE) {
            this.storageRender.draw(graphics, mouseX, mouseY);
        } else {
            this.machineLibraryRender.draw(graphics);
        }

        graphics.drawString(this.context.font(), "合成", l.craftX, l.craftLabelY,
                this.context.theme().textPrimary(), false);
        graphics.drawString(this.context.font(), "背包", l.invX, l.invLabelY,
                this.context.theme().textPrimary(), false);
    }
}
