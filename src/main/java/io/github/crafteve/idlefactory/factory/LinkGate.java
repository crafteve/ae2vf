package io.github.crafteve.idlefactory.factory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.links.LinkRegistry;
import io.github.crafteve.idlefactory.factory.links.LinkRole;
import io.github.crafteve.idlefactory.location.BlockKey;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

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
     * 按占用表过滤一批目标：空地和自己的同角色目标放行，别人的和别人角色的分别计数。
     *
     * <p>连锁是“先按世界事实找全，再按归属过滤”——BFS 的半径与数量上限都在
     * {@code FactoryLinkFinder} 里按筛选前计算，这里只分流，过滤后不补位、不重扫。</p>
     *
     * @param factoryId 当前工厂
     * @param role      本次操作的角色
     * @param dimension 所在维度
     * @param targets   候选坐标
     * @return 过滤结果
     */
    public Filter filter(UUID factoryId, LinkRole role, ResourceKey<Level> dimension,
            List<BlockPos> targets) {
        List<BlockPos> open = new ArrayList<>(targets.size());
        int blockedOther = 0;
        int blockedRole = 0;
        for (BlockPos targetPos : targets) {
            ClaimCheck check = this.check(BlockKey.of(dimension, targetPos), factoryId, role);
            switch (check.kind()) {
                case FREE, SELF_SAME -> open.add(targetPos);
                case SELF_OTHER -> blockedRole++;
                case OTHER -> blockedOther++;
            }
        }
        return new Filter(open, blockedOther, blockedRole);
    }

    /**
     * 一次过滤的结果：放行的目标，以及被别家占、角色冲突而跳过的数量。
     *
     * @param open         放行的目标坐标
     * @param blockedOther 被别的工厂占了的数量
     * @param blockedRole  位置属于自己、但角色不是本次操作角色的数量
     */
    public record Filter(List<BlockPos> open, int blockedOther, int blockedRole) {
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
