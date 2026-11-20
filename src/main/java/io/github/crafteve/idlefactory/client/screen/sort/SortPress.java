package io.github.crafteve.idlefactory.client.screen.sort;

/**
 * 排序按钮的点击回调。
 *
 * <p>左键正向、右键反向；两态按钮（方向）忽略方向参数。用一个带方向参数的函数式接口，
 * 是为了让按钮自己处理按键判定，屏幕只回答「怎么走」。</p>
 */
@FunctionalInterface
public interface SortPress {
    /**
     * @param reverse 右键反向时为 {@code true}
     */
    void press(boolean reverse);
}
