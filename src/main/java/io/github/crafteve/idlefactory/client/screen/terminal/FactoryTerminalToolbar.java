package io.github.crafteve.idlefactory.client.screen.terminal;

import java.lang.reflect.Field;

import appeng.client.Point;
import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.widgets.VerticalButtonBar;

/**
 * 把 AE2 的竖向按钮栏从终端左边缘挪到整个窗口最左。
 *
 * <p>按钮栏每次渲染都按 init 时缓存的原点重排按钮，只能改它自己的 {@code position}，
 * 不能事后平移按钮。字段在 AE2 里是私有的，访问转换器只作用于运行期、编译期拿不到，
 * 所以这里用反射；拿不到时保持默认位置，界面仍可用。</p>
 */
final class FactoryTerminalToolbar {
    private FactoryTerminalToolbar() {
        // 纯静态工具类
    }

    /**
     * 移动按钮栏。
     *
     * @param screen 终端屏幕
     */
    static void moveToWindowLeft(AEBaseScreen<?> screen) {
        try {
            Field field = AEBaseScreen.class.getDeclaredField("verticalToolbar");
            field.setAccessible(true);
            VerticalButtonBar bar = (VerticalButtonBar) field.get(screen);
            bar.setPosition(new Point(FactoryTerminalGeometry.toolbarOffsetX(), 0));
        } catch (ReflectiveOperationException | ClassCastException ignored) {
            // 反射失败只影响按钮栏位置，不影响终端本身。
        }
    }
}
