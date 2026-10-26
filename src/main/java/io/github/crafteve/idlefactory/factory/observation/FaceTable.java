package io.github.crafteve.idlefactory.factory.observation;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.resource.ResourceKind;

import net.minecraft.core.Direction;

/**
 * 一个方块位置的能力快照，按面组织。
 *
 * <p>两半：{@link #byFace()} 是「有方向查询」的结果；{@link #unsided()} 是
 * {@code getCapability(..., null)} 的结果。后者单独存是必要的——有些模组只注册无向能力，
 * 按面查全是 null，但整体可用。</p>
 *
 * <p>不可变。构造时会把所有集合复制一遍，调用方拿到的任何 Set 都不能改到内部状态。</p>
 *
 * @param byFace  面 → 该面提供的能力；没有能力的面不会出现在表里
 * @param unsided 无向查询报告的能力
 */
public record FaceTable(Map<Direction, Set<ResourceKind>> byFace,
                        Set<ResourceKind> unsided) {

    /** 什么都没有。 */
    public static final FaceTable EMPTY = new FaceTable(Map.of(), Set.of());

    public FaceTable {
        Map<Direction, Set<ResourceKind>> copy = new EnumMap<>(Direction.class);
        byFace.forEach((face, kinds) -> copy.put(face, immutable(kinds)));
        byFace = Collections.unmodifiableMap(copy);
        unsided = immutable(unsided);
    }

    /**
     * 这个位置是不是「有东西可用」。
     *
     * @return 任意面或无向有能力时为 {@code true}
     */
    public boolean any() {
        return !this.byFace.isEmpty() || !this.unsided.isEmpty();
    }

    /**
     * 某个面提供的能力。
     *
     * @param face 面；{@code null} 表示无向
     * @return 能力集合（只读，空集表示没有）
     */
    public Set<ResourceKind> at(@Nullable Direction face) {
        if (face == null) {
            return this.unsided;
        }
        return this.byFace.getOrDefault(face, Set.of());
    }

    /**
     * 有某种能力的所有面（顺序 = {@link Direction} 枚举顺序，稳定）。
     *
     * @param kind 资源类型
     * @return 面集合；可能为空
     */
    public EnumSet<Direction> facesFor(ResourceKind kind) {
        EnumSet<Direction> result = EnumSet.noneOf(Direction.class);
        for (Map.Entry<Direction, Set<ResourceKind>> entry : this.byFace.entrySet()) {
            if (entry.getValue().contains(kind)) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /**
     * 无向查询是否报告了某种能力。
     *
     * @param kind 资源类型
     * @return 有则为 {@code true}
     */
    public boolean hasUnsided(ResourceKind kind) {
        return this.unsided.contains(kind);
    }

    /**
     * 所有面的能力并集（不含无向）。
     *
     * @return 只读集合
     */
    public Set<ResourceKind> union() {
        EnumSet<ResourceKind> all = EnumSet.noneOf(ResourceKind.class);
        this.byFace.values().forEach(all::addAll);
        return Collections.unmodifiableSet(all);
    }

    /**
     * 给界面用的一行摘要：{@code item+fluid+energy}，没有能力时是 {@code none}。
     *
     * @return 摘要文本
     */
    public String describe() {
        return describe(this.union());
    }

    /**
     * 把一组能力写成一行摘要。
     *
     * @param kinds 能力集合
     * @return 摘要文本；空集合返回 {@code none}
     */
    public static String describe(Collection<ResourceKind> kinds) {
        if (kinds.isEmpty()) {
            return "none";
        }
        StringBuilder builder = new StringBuilder();
        for (ResourceKind kind : kinds) {
            if (builder.length() > 0) {
                builder.append('+');
            }
            builder.append(kind.name().toLowerCase(Locale.ROOT));
        }
        return builder.toString();
    }

    private static Set<ResourceKind> immutable(Collection<ResourceKind> kinds) {
        EnumSet<ResourceKind> set = EnumSet.noneOf(ResourceKind.class);
        set.addAll(kinds);
        return Collections.unmodifiableSet(set);
    }
}
