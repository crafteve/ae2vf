package io.github.crafteve.idlefactory.factory.links;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryManager;
import io.github.crafteve.idlefactory.factory.machine.MachineRef;
import io.github.crafteve.idlefactory.factory.location.BlockKey;
import io.github.crafteve.idlefactory.factory.storage.Ae2Anchor;
import io.github.crafteve.idlefactory.factory.storage.ContainerAnchor;
import io.github.crafteve.idlefactory.factory.storage.ContainerRef;
import io.github.crafteve.idlefactory.factory.storage.MeAnchor;
import io.github.crafteve.idlefactory.factory.storage.StorageAnchor;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * 全服占用索引：统一 id → 认领。
 *
 * <p>它只是真相的索引，不是真相本身——真相永远在各工厂的记录里。它只回答“这个位置
 * 被谁以什么角色占了”，健康、面表、IO 状态一概不管，那些是观察层和执行层的事。</p>
 *
 * <p>本阶段用法：调用方现建现用，{@link #rebuild} 从档案拼一份出来查；P1 再把它接成
 * 常驻实例做门禁。所以它不落盘、不跟存档打交道，重启即重建，不可能和存档分歧。</p>
 */
public final class LinkRegistry {
    /**
     * 一条认领：谁的工厂、以什么角色占着。
     *
     * @param factoryId 归属工厂 id
     * @param role      占用角色
     */
    public record Claim(UUID factoryId, LinkRole role) {
    }

    /**
     * 一条冲突：同一个 id 被两家占了（跨工厂，或同工厂里机器仓库重叠）。
     *
     * @param dimension 位置所在维度
     * @param pos       位置坐标
     * @param first     先登记的那家（保留生效）
     * @param second    后登记的那家（被拒绝/仅报告）
     */
    public record Conflict(ResourceKey<Level> dimension, BlockPos pos,
            Claim first, Claim second) {
    }

    private final Map<UUID, Claim> byId = new HashMap<>();
    private final Map<UUID, Set<UUID>> byFactory = new HashMap<>();
    /**
     * 区块 → 该区块内的占用键（带坐标）。
     *
     * <p>值用 {@link BlockKey} 而不用 id：chunk 事件过来时只有坐标，UUID 反解不出
     * 坐标——值里带坐标，事件过滤和复探才不用回工厂翻记录。</p>
     */
    private final Map<ChunkPos, Set<BlockKey>> byChunk = new HashMap<>();

    /**
     * 从档案重建整张表。启动、删档、对账时调；调一次就是一次全量自愈。
     *
     * <p>键一律按坐标现派生（{@link LinkIds}），不信任记录里存的 id——存量漂移了也对得上。
     * 冲突只报告不解决：先登记的保留生效，后到的进冲突列表。</p>
     *
     * @param manager 工厂档案柜
     * @return 冲突列表；没有冲突时为空
     */
    public List<Conflict> rebuild(FactoryManager manager) {
        this.byId.clear();
        this.byFactory.clear();
        this.byChunk.clear();
        List<Conflict> conflicts = new ArrayList<>();
        for (Factory factory : manager.all()) {
            for (MachineRef ref : factory.machines().all()) {
                put(factory.id(), LinkRole.MACHINE, ref.dimension(), ref.pos(), conflicts);
            }
            StorageAnchor anchor = factory.storageLink().anchor();
            if (anchor instanceof MeAnchor me) {
                put(factory.id(), LinkRole.STORAGE, me.dimension(), me.pos(), conflicts);
            } else if (anchor instanceof Ae2Anchor ae2) {
                put(factory.id(), LinkRole.STORAGE, ae2.dimension(), ae2.ringPos(), conflicts);
            } else if (anchor instanceof ContainerAnchor containers) {
                for (ContainerRef ref : containers.containers()) {
                    put(factory.id(), LinkRole.STORAGE, ref.dimension(), ref.pos(), conflicts);
                }
            }
        }
        return conflicts;
    }

    /**
     * 按统一 id 查占用。
     *
     * @param id 统一 id（{@link LinkIds} 派生）
     * @return 认领；没人占时为空
     */
    public Optional<Claim> get(UUID id) {
        return Optional.ofNullable(this.byId.get(id));
    }

    /**
     * 某工厂名下所有占用 id。
     *
     * @param factoryId 工厂 id
     * @return 只读集合；没有时为空集
     */
    public Set<UUID> byFactory(UUID factoryId) {
        Set<UUID> found = this.byFactory.get(factoryId);
        return found == null ? Set.of() : Collections.unmodifiableSet(found);
    }

    /**
     * 某区块内所有占用键（带坐标，事件过滤、chunk 加载复探用）。
     *
     * @param chunk 区块坐标
     * @return 只读集合；没有时为空集
     */
    public Set<BlockKey> byChunk(ChunkPos chunk) {
        Set<BlockKey> found = this.byChunk.get(chunk);
        return found == null ? Set.of() : Collections.unmodifiableSet(found);
    }

    /** 当前占用条数。 */
    public int size() {
        return this.byId.size();
    }

    /** 是否为空。 */
    public boolean isEmpty() {
        return this.byId.isEmpty();
    }

    private void put(UUID factoryId, LinkRole role, ResourceKey<Level> dimension, BlockPos pos,
            List<Conflict> conflicts) {
        UUID id = LinkIds.of(dimension, pos);
        Claim existing = this.byId.get(id);
        Claim claim = new Claim(factoryId, role);
        if (existing != null && (!existing.factoryId().equals(factoryId)
                || existing.role() != role)) {
            // 同工厂同角色重复出现只是去重问题，不算冲突；跨工厂或跨角色才报告
            conflicts.add(new Conflict(dimension, pos, existing, claim));
            return;
        }
        this.byId.put(id, claim);
        this.byFactory.computeIfAbsent(factoryId, ignored -> new HashSet<>()).add(id);
        this.byChunk.computeIfAbsent(new ChunkPos(pos), ignored -> new HashSet<>())
                .add(new BlockKey(dimension, pos));
    }
}
