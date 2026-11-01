package io.github.crafteve.idlefactory.client.screen.terminal;

import java.lang.reflect.Field;
import java.util.List;

import appeng.client.Point;
import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.widgets.VerticalButtonBar;

import net.minecraft.client.gui.components.Button;

/**
 * 操作 AE2 的竖向按钮栏：挪到窗口最左、把自定义按钮插到栏首。
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
        VerticalButtonBar bar = barOf(screen);
        if (bar != null) {
            bar.setPosition(new Point(FactoryTerminalGeometry.toolbarOffsetX(), 0));
        }
    }

    /**
     * 把按钮插到栏首，避免追加到栏底和 AE2 的设置按钮隔开。
     *
     * @param screen 终端屏幕
     * @param button 要提前的按钮
     */
    static void moveToTop(AEBaseScreen<?> screen, Button button) {
        VerticalButtonBar bar = barOf(screen);
        if (bar == null) {
            return;
        }
        try {
            Field field = VerticalButtonBar.class.getDeclaredField("buttons");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Button> buttons = (List<Button>) field.get(bar);
            buttons.remove(button);
            buttons.add(0, button);
        } catch (ReflectiveOperationException | ClassCastException ignored) {
            // 失败就让按钮留在末尾，不阻塞其它功能。
        }
    }

    private static VerticalButtonBar barOf(AEBaseScreen<?> screen) {
        try {
            Field field = AEBaseScreen.class.getDeclaredField("verticalToolbar");
            field.setAccessible(true);
            return (VerticalButtonBar) field.get(screen);
        } catch (ReflectiveOperationException | ClassCastException ignored) {
            return null;
        }
    }
}
