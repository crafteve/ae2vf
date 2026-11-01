package io.github.crafteve.idlefactory.menu;

import appeng.menu.SlotSemantic;
import appeng.menu.SlotSemantics;

/**
 * 工厂终端自己的槽位语义。
 *
 * <p>AE2 的样式 JSON 只认识它自己的语义槽；我们的样板槽不在 JSON 里，所以要注册一个
 * 语义，再由屏幕按左面板的几何手动摆位。`playerSide` 为真：这 9 格允许玩家直接存取。</p>
 */
public final class FactoryPatternSlots {
    /** 主动样板槽。 */
    public static final SlotSemantic PATTERN_SLOT =
            SlotSemantics.register("IDLEFACTORY_PATTERN_SLOT", true);

    private FactoryPatternSlots() {
        // 纯常量持有类
    }
}
