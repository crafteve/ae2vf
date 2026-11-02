package io.github.crafteve.idlefactory.factory.machine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * <p>方块没了不会把机器移出机器库，只由运行时 {@code machineMissing} 标记；只有玩家显式删除
 * 才会真正移除。</p>
 */
public final class MachineLibrary {
    /**
     * 机器库容量上限：一台工厂最多接管多少台机器。
     *
     * <p>这个值同时是 {@code FactoryTableS2C} 的解码上限，改这里等于改线协议，必须同步递增
     * {@code ModNetwork.PROTOCOL_VERSION}。与 {@code FactoryLinkFinder.MAX_MACHINES}
     * （BFS 扫描上限 128）同名不同义，禁止静态导入，引用时必须写全
     * {@code MachineLibrary.MAX_MACHINES}。</p>
     */
    public static final int MAX_MACHINES = 512;

    private final Map<UUID, Machine> byId = new LinkedHashMap<>();

    /**
     * 一次加入的结果。
     *
     * <p>不用 boolean：调用方要区分「id 重复」和「库满」——前者是「已接入过」，
     * 后者要给上限回执；混成一个 {@code false} 会让第 513 台被误报成重复接入。</p>
     */
    public enum AddResult {
        /** 已加入。 */
        ADDED,
        /** id 已存在，未加入。 */
        DUPLICATE,
        /** 机器库已满，未加入。 */
        FULL
    }

    /**
     * 按坐标加一台没有规则的新机器。
     *
     * @param target 机器身份
     * @return 加入结果；已存在返回 {@link AddResult#DUPLICATE}，库满返回 {@link AddResult#FULL}
     */
    public AddResult add(MachineRef target) {
        if (this.byId.containsKey(target.id())) {
            return AddResult.DUPLICATE;
        }
        if (this.byId.size() >= MAX_MACHINES) {
            return AddResult.FULL;
        }
        this.byId.put(target.id(), new Machine(target));
        return AddResult.ADDED;
    }

    /**
     * 加一台已经带规则的机器（读档用）。
     *
     * @param machine 机器
     * @return 加入结果；id 已存在返回 {@link AddResult#DUPLICATE}，库满返回 {@link AddResult#FULL}
     */
    public AddResult add(Machine machine) {
        if (this.byId.containsKey(machine.id())) {
            return AddResult.DUPLICATE;
        }
        if (this.byId.size() >= MAX_MACHINES) {
            return AddResult.FULL;
        }
        this.byId.put(machine.id(), machine);
        return AddResult.ADDED;
    }

    /**
     * 按 id 移除。
     *
     * @param id 机器 id
     * @return 是否真的移除了
     */
    public boolean remove(UUID id) {
        return this.byId.remove(id) != null;
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

    /** 所有机器（登记顺序）。 */
    public List<Machine> all() {
        return List.copyOf(this.byId.values());
    }

    /** 机器数量。 */
    public int size() {
        return this.byId.size();
    }

    /** 是否为空。 */
    public boolean isEmpty() {
        return this.byId.isEmpty();
    }
}
