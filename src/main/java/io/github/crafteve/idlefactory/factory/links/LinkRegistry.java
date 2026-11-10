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
import io.github.crafteve.idlefactory.factory.location.BlockKey;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.node.Node;
import io.github.crafteve.idlefactory.factory.location.BlockKey;
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

    private final Map<UUID, Claim> byId = new HashMap<>();
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
     * <p>键一律按坐标现派生（{@link BlockKey#id()}），不信任记录里存的 id——存量漂移了也对得上。
     * 冲突按「先登记的保留生效、后到的忽略」处理：原本还会把冲突收集成列表返回给
     * {@code /idf diag links}，指令删除后没有读者，冲突列表一并去掉。</p>
     *
     * @param manager 工厂档案柜
     */
    public void rebuild(FactoryManager manager) {
        this.byId.clear();
        this.byChunk.clear();
        for (Factory factory : manager.all()) {
            for (Node node : factory.config().nodes().all()) {
                for (Machine machine : node.machines()) {
                    BlockKey block = machine.block();
                    put(factory.id(), LinkRole.MACHINE, block.dimension(), block.pos());
                }
            }
            StorageAnchor anchor = factory.config().storageAnchor();
            if (anchor instanceof MeAnchor me) {
                put(factory.id(), LinkRole.STORAGE, me.dimension(), me.pos());
            } else if (anchor instanceof ContainerAnchor containers) {
                for (ContainerRef ref : containers.containers()) {
                    put(factory.id(), LinkRole.STORAGE, ref.dimension(), ref.pos());
                }
            }
        }
    }

    /**
     * 按统一 id 查占用。
     *
     * @param id 统一 id（{@link BlockKey#id()} 派生）
     * @return 认领；没人占时为空
     */
    public Optional<Claim> get(UUID id) {
        return Optional.ofNullable(this.byId.get(id));
    }

    /**
     * 只读的 id → 认领表。
     *
     * <p>给门禁现算一次操作视图用；调用方拿到的是只读视图，不能改内部索引。</p>
     *
     * @return 只读映射
     */
    public Map<UUID, Claim> claims() {
        return Collections.unmodifiableMap(this.byId);
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

    /** 是否为空。 */
    public boolean isEmpty() {
        return this.byId.isEmpty();
    }

    private void put(UUID factoryId, LinkRole role, ResourceKey<Level> dimension, BlockPos pos) {
        UUID id = BlockKey.of(dimension, pos).id();
        Claim existing = this.byId.get(id);
        Claim claim = new Claim(factoryId, role);
        if (existing != null && (!existing.factoryId().equals(factoryId)
                || existing.role() != role)) {
            // 同工厂同角色重复出现只是去重问题；跨工厂或跨角色保留先登记者，后到的忽略
            return;
        }
        this.byId.put(id, claim);
        this.byChunk.computeIfAbsent(new ChunkPos(pos), ignored -> new HashSet<>())
                .add(new BlockKey(dimension, pos));
    }
}
