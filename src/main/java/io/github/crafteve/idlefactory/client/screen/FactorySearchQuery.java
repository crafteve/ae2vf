package io.github.crafteve.idlefactory.client.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 与 AE2 终端一致的搜索语法。
 *
 * <ul>
 *   <li>空格 = AND，{@code |} = OR；</li>
 *   <li>{@code @} 模组、{@code *} 注册名、{@code #} 标签、{@code $} tooltip；</li>
 *   <li>其余按显示名；</li>
 *   <li>空查询匹配全部。</li>
 * </ul>
 *
 * <p>纯逻辑，不读世界、不依赖界面；文本比较由调用方传入，默认可用
 * {@link FactoryTextMatcher#contains(String, String)} 获得拼音兼容。</p>
 */
public final class FactorySearchQuery {
    /** 空查询。 */
    public static final FactorySearchQuery EMPTY =
            new FactorySearchQuery(List.of());

    private final List<List<Term>> orGroups;

    private FactorySearchQuery(List<List<Term>> orGroups) {
        this.orGroups = orGroups;
    }

    /**
     * 文本匹配器。
     */
    @FunctionalInterface
    public interface TextMatcher {
        /**
         * @param text 被搜索文本
         * @param term 搜索词
         * @return 是否匹配
         */
        boolean contains(String text, String term);
    }

    /**
     * 解析查询。
     *
     * @param raw     原始输入
     * @param matcher 文本匹配器
     * @return 查询；空输入返回 {@link #EMPTY}
     */
    public static FactorySearchQuery parse(String raw, TextMatcher matcher) {
        if (raw == null || raw.trim().isEmpty()) {
            return EMPTY;
        }
        List<List<Term>> groups = new ArrayList<>();
        for (String orPart : raw.split("\\|", -1)) {
            List<Term> terms = new ArrayList<>();
            for (String part : orPart.trim().split("\\s+")) {
                if (part.isEmpty()) {
                    continue;
                }
                Term term = term(part, matcher);
                if (term != null) {
                    terms.add(term);
                }
            }
            if (!terms.isEmpty()) {
                groups.add(List.copyOf(terms));
            }
        }
        return groups.isEmpty() ? EMPTY : new FactorySearchQuery(List.copyOf(groups));
    }

    /**
     * 是否为空查询。
     *
     * @return 空查询匹配全部
     */
    public boolean isEmpty() {
        return this.orGroups.isEmpty();
    }

    /**
     * 匹配一组字段。
     *
     * @param fields 字段
     * @return 是否命中
     */
    public boolean matches(Fields fields) {
        if (this.isEmpty()) {
            return true;
        }
        for (List<Term> group : this.orGroups) {
            boolean all = true;
            for (Term term : group) {
                if (!term.matches(fields)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    private static Term term(String part, TextMatcher matcher) {
        String lower = part.toLowerCase(Locale.ROOT);
        if (lower.startsWith("@")) {
            String value = lower.substring(1);
            return value.isEmpty() ? null : new ModTerm(value, matcher);
        }
        if (lower.startsWith("*")) {
            String value = lower.substring(1);
            return value.isEmpty() ? null : new IdTerm(value);
        }
        if (lower.startsWith("#")) {
            String value = lower.substring(1);
            return value.isEmpty() ? null : new TagTerm(value);
        }
        if (lower.startsWith("$")) {
            String value = lower.substring(1);
            return value.isEmpty() ? null : new TooltipTerm(value, matcher);
        }
        return new NameTerm(lower, matcher);
    }

    /**
     * 搜索字段。
     *
     * @param name      显示名（机器可包含产线名）
     * @param id        注册名
     * @param namespace 命名空间
     * @param modName   模组显示名
     * @param tooltip   tooltip/摘要文本
     * @param tags      标签 id
     */
    public record Fields(String name, String id, String namespace, String modName,
            String tooltip, List<String> tags) {

        public Fields {
            name = name == null ? "" : name;
            id = id == null ? "" : id;
            namespace = namespace == null ? "" : namespace;
            modName = modName == null ? "" : modName;
            tooltip = tooltip == null ? "" : tooltip;
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }

    private interface Term {
        boolean matches(Fields fields);
    }

    private record NameTerm(String term, TextMatcher matcher) implements Term {
        @Override
        public boolean matches(Fields fields) {
            return this.matcher.contains(fields.name(), this.term);
        }
    }

    private record ModTerm(String term, TextMatcher matcher) implements Term {
        @Override
        public boolean matches(Fields fields) {
            if (fields.namespace().toLowerCase(Locale.ROOT).contains(this.term)) {
                return true;
            }
            return this.matcher.contains(fields.modName(), this.term);
        }
    }

    private record IdTerm(String term) implements Term {
        @Override
        public boolean matches(Fields fields) {
            return fields.id().toLowerCase(Locale.ROOT).contains(this.term);
        }
    }

    private record TagTerm(String term) implements Term {
        @Override
        public boolean matches(Fields fields) {
            for (String tag : fields.tags()) {
                if (tag.toLowerCase(Locale.ROOT).contains(this.term)) {
                    return true;
                }
            }
            return false;
        }
    }

    private record TooltipTerm(String term, TextMatcher matcher) implements Term {
        @Override
        public boolean matches(Fields fields) {
            return this.matcher.contains(fields.tooltip(), this.term);
        }
    }
}
