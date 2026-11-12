package io.github.crafteve.idlefactory.factory.io;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.machine.MatchMode;
import appeng.api.stacks.AEKey;

import net.minecraft.core.Direction;

/**
 * 一条 IO 规则的运行时身份。
 *
 * <p>同一台机器的同一侧，同一资源只允许一条规则，所以 {@code side + key + face + mode}
 * 足以唯一标识一条规则；面从 any 改成具体方向、或匹配模式变化时视为新规则，旧速率不会串过去。</p>
 *
 * <p>方向用 {@link IoSide} 而不是 {@code boolean input}：调用点不再出现猜参数含义的写法，
 * 与表单、账本、网络层用的是同一个枚举。</p>
 *
 * @param side 方向：输入还是输出
 * @param key  资源
 * @param face 面；{@code null} 表示 any
 * @param mode 物品匹配模式
 */
public record RuleKey(IoSide side, AEKey key, @Nullable Direction face, MatchMode mode) {
}
