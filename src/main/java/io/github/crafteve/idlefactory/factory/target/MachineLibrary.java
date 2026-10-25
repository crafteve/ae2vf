package io.github.crafteve.idlefactory.factory.target;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 工厂的机器库：一组被接管的目标方块。
 *
 * <p>只存坐标和 id，不存方块实体。增删都按 id 去重；按坐标查用于准星操作，按短 id 查用于
 * 远程清理。</p>
 */
public final class MachineLibrary {
    private final Map<UUID, MachineTarget> byId = new LinkedHashMap<>();

    /**
     * 加一个目标。
     *
     * @param target 目标
     * @return 是否真的加入；已存在时返回 {@code false}
     */
    public boolean add(MachineTarget target) {
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
    public void addAll(Collection<MachineTarget> targets) {
        for (MachineTarget target : targets) {
            add(target);
        }
    }

    /**
     * 按 id 移除。
     *
     * @param id 目标 id
     * @return 是否真的移除了
     */
    public boolean remove(UUID id) {
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
        return this.byId.values().removeIf(target ->
                target.dimension().equals(dimension) && target.pos().equals(pos));
    }

    /**
     * 按短 id 查找（前 8 位）。
     *
     * @param shortId 短 id
     * @return 目标；找不到或前缀有歧义时返回空
     */
    public Optional<MachineTarget> byShortId(String shortId) {
        MachineTarget found = null;
        for (MachineTarget target : this.byId.values()) {
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
    public Optional<MachineTarget> byId(UUID id) {
        return Optional.ofNullable(this.byId.get(id));
    }

    /** 所有目标（登记顺序）。 */
    public List<MachineTarget> all() {
        return List.copyOf(this.byId.values());
    }

    /** 目标数量。 */
    public int size() {
        return this.byId.size();
    }

    /** 是否为空。 */
    public boolean isEmpty() {
        return this.byId.isEmpty();
    }
}
