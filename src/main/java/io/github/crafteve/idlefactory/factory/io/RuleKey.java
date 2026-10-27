package io.github.crafteve.idlefactory.factory.io;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.resource.MatchMode;
import io.github.crafteve.idlefactory.factory.resource.ResourceKey;

import net.minecraft.core.Direction;

/**
 * 一条 IO 规则的运行时身份。
 *
 * <p>同一台机器的同一侧，同一资源只允许一条规则，所以 {@code input + key + face + mode}
 * 足以唯一标识一条规则；面从 any 改成具体方向、或匹配模式变化时视为新规则，旧速率不会串过去。</p>
 *
 * @param input 是否输入；{@code false} 表示输出
 * @param key   资源
 * @param face  面；{@code null} 表示 any
 * @param mode  物品匹配模式
 */
public record RuleKey(boolean input, ResourceKey key, @Nullable Direction face, MatchMode mode) {
}
