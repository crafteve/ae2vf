package io.github.crafteve.idlefactory.integration.jecharacters;

import java.lang.reflect.Method;
import java.util.Locale;

import org.jetbrains.annotations.Nullable;

/**
 * jecharacters 的拼音搜索兼容层。
 *
 * <p>AE2 终端的拼音搜索不是它自己实现的，而是 jecharacters 在 ASM 层改写了
 * {@code NameSearchPredicate} / {@code TooltipsSearchPredicate} 里的 contains。我们自己的
 * 搜索类不在它的 targets 里，所以必须显式接它的公开 API。</p>
 *
 * <p>放在 integration 下，是因为这是外部模组适配，不是界面逻辑；不在 build.gradle 里硬依赖
 * jecharacters：反射探测，类缺失时行为退回原版，API 变动也不会让工作台崩溃。</p>
 */
public final class JecharactersCompat {
    @Nullable
    private static final Method MATCH_CONTAINS = findMatchContains();

    private JecharactersCompat() {
        // 纯静态工具类
    }

    /**
     * 判断 text 是否包含 term（支持 jecharacters 的拼音/首字母匹配）。
     *
     * @param text 被搜索文本
     * @param term 搜索词
     * @return 是否匹配
     */
    public static boolean contains(@Nullable String text, @Nullable String term) {
        if (text == null || term == null) {
            return false;
        }
        String haystack = text.toLowerCase(Locale.ROOT);
        String needle = term.toLowerCase(Locale.ROOT);
        if (MATCH_CONTAINS != null) {
            try {
                return (Boolean) MATCH_CONTAINS.invoke(null, haystack, needle);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // 回退到普通 contains；不把兼容层错误带进界面
            }
        }
        return haystack.contains(needle);
    }

    @Nullable
    private static Method findMatchContains() {
        try {
            Class<?> match = Class.forName("me.towdium.jecharacters.utils.Match");
            return match.getMethod("contains", CharSequence.class, CharSequence.class);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }
}
