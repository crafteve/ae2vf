package io.github.crafteve.idlefactory.factory.machine;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 工厂的机器库：一组被接管的目标方块。
 *
 * <p>只存坐标和 id，不存方块实体。增删都按 id 去重；按坐标查用于准星操作，按短 id 查用于
 * 远程清理。</p>
 *
 * <p>另存一个 {@code retired} 集合：它记的是<b>玩家的意图</b>（「这台我不要了」），不是世界事实。
 * 事实在 {@code observation} 包里。两者正交，所以一台机器可以同时是「已退役」和「方块已消失」。</p>
 */
public final class MachineLibrary {
    private final Map<UUID, MachineRef> byId = new LinkedHashMap<>();
    /** 玩家已经退役（放进回收站）、但仍留在库里的机器。写存档。 */
    private final Set<UUID> retired = new HashSet<>();

    /**
     * 加一个目标。
     *
     * @param target 目标
     * @return 是否真的加入；已存在时返回 {@code false}
     */
    public boolean add(MachineRef target) {
        if (this.byId.containsKey(target.id())) {
            return false;
        }
        this.byId.put(target.id(), target);
        return true;
    }

    /**
     * 批量加，忽略重复。
     *
     * @param targets 目标集合
     */
    public void addAll(Collection<MachineRef> targets) {
        for (MachineRef target : targets) {
            add(target);
        }
    }

    /**
     * 按 id 移除。会同时清掉它的退役标记。
     *
     * @param id 目标 id
     * @return 是否真的移除了
     */
    public boolean remove(UUID id) {
        this.retired.remove(id);
        return this.byId.remove(id) != null;
    }

    /**
     * 按坐标移除。
     *
     * @param dimension 维度
     * @param pos       坐标
     * @return 是否真的移除了
     */
    public boolean remove(ResourceKey<Level> dimension, BlockPos pos) {
        return removeAt(dimension, pos) != null;
    }

    /**
     * 按坐标移除，并返回被移除的目标。
     *
     * @param dimension 维度
     * @param pos       坐标
     * @return 被移除的目标；这个位置没有登记机器时为 {@code null}
     */
    @Nullable
    public MachineRef removeAt(ResourceKey<Level> dimension, BlockPos pos) {
        MachineRef found = byPosition(dimension, pos);
        if (found == null) {
            return null;
        }
        remove(found.id());
        return found;
    }

    /**
     * 按坐标查。
     *
     * @param dimension 维度
     * @param pos       坐标
     * @return 目标；没有时为 {@code null}
     */
    @Nullable
    public MachineRef byPosition(ResourceKey<Level> dimension, BlockPos pos) {
        for (MachineRef target : this.byId.values()) {
            if (target.dimension().equals(dimension) && target.pos().equals(pos)) {
                return target;
            }
        }
        return null;
    }

    /**
     * 按短 id 查找（前 8 位）。
     *
     * @param shortId 短 id
     * @return 目标；找不到或前缀有歧义时返回空
     */
    public Optional<MachineRef> byShortId(String shortId) {
        MachineRef found = null;
        for (MachineRef target : this.byId.values()) {
            if (target.shortId().equalsIgnoreCase(shortId)) {
                if (found != null) {
                    // 前缀撞了，宁可报错也不要删错
                    return Optional.empty();
                }
                found = target;
            }
        }
        return Optional.ofNullable(found);
    }

    /** 按 id 查。 */
    public Optional<MachineRef> byId(UUID id) {
        return Optional.ofNullable(this.byId.get(id));
    }

    /**
     * 这台机器是不是已被玩家退役（放进回收站）。
     *
     * @param id 目标 id
     * @return 已退役时为 {@code true}
     */
    public boolean isRetired(UUID id) {
        return this.retired.contains(id);
    }

    /**
     * 设置退役标记。只会作用于库里已有的机器。
     *
     * @param id    目标 id
     * @param value {@code true} 表示移入回收站
     */
    public void setRetired(UUID id, boolean value) {
        if (!this.byId.containsKey(id)) {
            return;
        }
        if (value) {
            this.retired.add(id);
        } else {
            this.retired.remove(id);
        }
    }

    /**
     * 所有退役标记（存档用）。
     *
     * @return 只读集合
     */
    public Set<UUID> retiredIds() {
        return Set.copyOf(this.retired);
    }

    /** 所有目标（登记顺序）。 */
    public List<MachineRef> all() {
        return List.copyOf(this.byId.values());
    }

    /** 目标数量（含回收站）。 */
    public int size() {
        return this.byId.size();
    }

    /** 还在参与 IO 的数量（不含回收站）。 */
    public int activeCount() {
        return this.byId.size() - this.retired.size();
    }

    /** 是否为空。 */
    public boolean isEmpty() {
        return this.byId.isEmpty();
    }
}
