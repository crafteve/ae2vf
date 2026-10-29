package io.github.crafteve.idlefactory.factory;

import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.links.LinkRegistry;
import io.github.crafteve.idlefactory.factory.links.LinkRole;
import io.github.crafteve.idlefactory.factory.location.BlockKey;

/**
 * 一次绑定操作的占用表。
 *
 * <p>它从工厂档案现算，供本次操作 O(1) 查表：连锁绑定先扫一遍全库（O(总数)），再对每个
 * 目标查表，避免逐台扫描。<b>它不是权威，也不持久化</b>——权威永远是工厂档案；这张表只在
 * 一次操作内有效，用完即弃。</p>
 *
 * <p>占用规则是<b>全局唯一</b>：任何工厂、任何角色，同一个位置只能有一条认领。释放是另一条
 * 规则：只有「自己的 + 同角色」才允许。判定结果用 {@link Kind} 区分，文案由调用方生成。</p>
 *
 * <p>注意：连锁查找的范围与数量上限（{@code FactoryLinkFinder} 的半径、访问数、结果数）
 * 都在<b>过滤前</b>计算；本表只负责分流，过滤后不补位、不重扫。</p>
 */
public final class LinkGate {
    /** 本次操作的占用表：统一 id → 认领。 */
    private final Map<UUID, LinkRegistry.Claim> claims;

    private LinkGate(Map<UUID, LinkRegistry.Claim> claims) {
        this.claims = claims;
    }

    /**
     * 从工厂档案扫一张占用表。
     *
     * <p>每次绑定操作调一次；扫描是 O(全部机器 + 容器)，只发生在人手操作时。</p>
     *
     * @param manager 工厂档案柜
     * @return 本次操作的占用表
     */
    public static LinkGate of(FactoryManager manager) {
        LinkRegistry registry = new LinkRegistry();
        registry.rebuild(manager);
        return new LinkGate(Map.copyOf(registry.claims()));
    }

    /**
     * 查一个位置对某个工厂、某个角色的占用情况。
     *
     * @param key       位置键
     * @param factoryId 发起操作的工厂
     * @param role      这次想以什么角色占用
     * @return 判定结果
     */
    public ClaimCheck check(BlockKey key, UUID factoryId, LinkRole role) {
        LinkRegistry.Claim claim = this.claims.get(key.id());
        if (claim == null) {
            return new ClaimCheck(null, ClaimCheck.Kind.FREE);
        }
        if (!claim.factoryId().equals(factoryId)) {
            return new ClaimCheck(claim, ClaimCheck.Kind.OTHER);
        }
        return new ClaimCheck(claim, claim.role() == role
                ? ClaimCheck.Kind.SELF_SAME : ClaimCheck.Kind.SELF_OTHER);
    }

    /**
     * 一次查表的结果。
     *
     * @param claim 当前认领；{@link Kind#FREE} 时为 {@code null}
     * @param kind  判定
     */
    public record ClaimCheck(@Nullable LinkRegistry.Claim claim, Kind kind) {

        /** 判定种类。 */
        public enum Kind {
            /** 没人占，可以绑定。 */
            FREE,
            /** 自己占着同角色：可以释放，或提示「已经接入」。 */
            SELF_SAME,
            /** 自己占着异角色：拒绝，提示先解除另一角色。 */
            SELF_OTHER,
            /** 别的工厂占着：拒绝。 */
            OTHER
        }

        /** 是否应该拒绝这次操作。 */
        public boolean blocked() {
            return this.kind == Kind.SELF_OTHER || this.kind == Kind.OTHER;
        }
    }
}
