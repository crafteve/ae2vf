package io.github.crafteve.idlefactory.factory.machine;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 工厂的机器库：一组被接管的机器。
 *
 * <p>每台机器是 {@link Machine}（身份 + 坐标 + IO 规则）。增删按 id 去重；按坐标查用于准星操作，
 * 按短 id 查用于远程操作。</p>
 *
 * <p>{@code retired} 是回收站过渡期字段，W4 会删除；目前仍供旧的断开/回收站逻辑使用。</p>
 */
public final class MachineLibrary {
    private final Map<UUID, Machine> byId = new LinkedHashMap<>();
    /** 玩家已退役、仍留在库里的机器 id（回收站过渡期使用）。 */
    private final Set<UUID> retired = new HashSet<>();

    /**
     * 按坐标加一台没有规则的新机器。
     *
     * @param target 机器身份
     * @return 是否真的加入；已存在时返回 {@code false}
     */
    public boolean add(MachineRef target) {
        if (this.byId.containsKey(target.id())) {
            return false;
        }
        this.byId.put(target.id(), new Machine(target));
        return true;
    }

    /**
     * 加一台已经带规则的机器（读档用）。
     *
     * @param machine 机器
     * @return 是否真的加入；id 已存在时返回 {@code false}
     */
    public boolean add(Machine machine) {
        if (this.byId.containsKey(machine.id())) {
            return false;
        }
        this.byId.put(machine.id(), machine);
        return true;
    }

    /**
     * 批量加，忽略重复。
     *
     * @param machines 机器集合
     */
    public void addAll(Collection<Machine> machines) {
        for (Machine machine : machines) {
            add(machine);
        }
    }

    /**
     * 按 id 移除。会同时清掉它的退役标记。
     *
     * @param id 机器 id
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
        Machine found = byPosition(dimension, pos);
        return found != null && remove(found.id());
    }

    /**
     * 按坐标找机器。
     *
     * @param dimension 维度
     * @param pos       坐标
     * @return 机器；不在库里时为 {@code null}
     */
    public Machine byPosition(ResourceKey<Level> dimension, BlockPos pos) {
        for (Machine machine : this.byId.values()) {
            MachineRef ref = machine.ref();
            if (ref.dimension().equals(dimension) && ref.pos().equals(pos)) {
                return machine;
            }
        }
        return null;
    }

    /**
     * 按短 id 找机器。
     *
     * @param shortId 短 id
     * @return 机器；找不到或前缀有歧义时为空
     */
    public Optional<Machine> byShortId(String shortId) {
        Machine found = null;
        for (Machine machine : this.byId.values()) {
            if (machine.ref().shortId().equalsIgnoreCase(shortId)) {
                if (found != null) {
                    return Optional.empty();
                }
                found = machine;
            }
        }
        return Optional.ofNullable(found);
    }

    /** 按 id 查。 */
    public Optional<Machine> byId(UUID id) {
        return Optional.ofNullable(this.byId.get(id));
    }

    /**
     * 这台机器是不是已被玩家退役（回收站）。
     *
     * @param id 机器 id
     * @return 已退役时为 {@code true}
     */
    public boolean isRetired(UUID id) {
        return this.retired.contains(id);
    }

    /**
     * 设置退役标记。只会作用于库里已有的机器。
     *
     * @param id    机器 id
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
     * 所有退役标记（存档过渡用）。
     *
     * @return 只读集合
     */
    public Set<UUID> retiredIds() {
        return Set.copyOf(this.retired);
    }

    /** 所有机器（登记顺序）。 */
    public List<Machine> all() {
        return List.copyOf(this.byId.values());
    }

    /** 机器数量（含回收站）。 */
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
