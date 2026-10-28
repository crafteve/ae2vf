package io.github.crafteve.idlefactory.factory.line;

/**
 * IO 规则的方向：输入还是输出。
 *
 * <p>命令、界面动作和网络包都传这个枚举，而不是 {@code boolean input}，避免调用点出现
 * {@code addRule(true)} 这种猜参数的写法。</p>
 */
public enum IoSide {
    /** 补料：仓库 → 机器。 */
    INPUT,
    /** 抽产物：机器 → 仓库。 */
    OUTPUT
}
