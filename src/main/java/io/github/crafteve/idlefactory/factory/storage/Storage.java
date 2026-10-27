package io.github.crafteve.idlefactory.factory.storage;

import java.util.Map;

import io.github.crafteve.idlefactory.factory.resource.ResourceKey;
import io.github.crafteve.idlefactory.factory.resource.ResourceKind;

/**
 * 工厂眼中的「仓库」。
 *
 * <p>工厂不实现存储，也不关心仓库背后是 AE2、方块容器还是空实现。它只问三件事：
 * 这个资源你能收多少、能给我多少、你支不支持这类资源。</p>
 *
 * <p>所有实现都必须是<b>事务安全</b>的：{@code simulate} 为 {@code true} 时不得产生任何副作用。</p>
 *
 * <p>物品的 {@link ResourceKey} 带完整数据组件，数量按精确变体计：同名不同组件的物品是两条
 * 独立记录，不会在快照里合并。</p>
 */
public interface Storage {
    /**
     * 往仓库里放东西。
     *
     * @param key      资源
     * @param amount   想放多少，必须为正
     * @param simulate 为 {@code true} 时只回报能收多少，不产生副作用
     * @return 实际收下的数量（{@code 0..amount}）
     */
    long insert(ResourceKey key, long amount, boolean simulate);

    /**
     * 从仓库里拿东西。
     *
     * @param key      资源
     * @param amount   想拿多少，必须为正
     * @param simulate 为 {@code true} 时只回报能拿多少，不产生副作用
     * @return 实际拿出的数量（{@code 0..amount}）
     */
    long extract(ResourceKey key, long amount, boolean simulate);

    /**
     * 这个仓库后端支不支持某类资源。
     *
     * @param kind 类型
     * @return 支持时为 {@code true}
     */
    boolean supports(ResourceKind kind);

    /** 仓库当前是否完全没有内容（只用于界面展示，不参与搬运判断）。 */
    boolean isEmpty();

    /**
     * 仓库里所有资源的快照。
     *
     * @return 资源 → 数量
     */
    Map<ResourceKey, Long> snapshot();
}
