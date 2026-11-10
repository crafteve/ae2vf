package io.github.crafteve.idlefactory.factory.node;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.location.BlockKey;
import io.github.crafteve.idlefactory.factory.machine.Machine;

/**
 * 工厂的节点库：节点集合 + 三张 O(1) 索引。
 *
 * <p>索引是 {@code byId}、{@code machineToNode}、{@code blockToOwner}。写 API 只有
 * {@link #add}、{@link #replace}、{@link #remove}，三种方法都是「先全量校验，再一次性写索引」：
 * 任何一条失败都不改索引，调用方从返回的 {@link Result} 区分原因。</p>
 *
 * <p>这里只守结构不变量与硬上限；config 的软上限由 {@code NodeLibraryService} 在调用前判。
 * 方块全局唯一：一个 {@link BlockKey} 只能属于一个节点的一台机器。</p>
 */
public final class NodeLibrary implements NodeLibraryView {
    /** 节点数硬上限，不可由 config 关掉。 */
    public static final int HARD_MAX_NODES = 4096;
    /** 单节点机器数硬上限。 */
    public static final int HARD_MAX_MACHINES_PER_NODE = 256;
    /** 全工厂机器数硬上限。 */
    public static final int HARD_MAX_TOTAL_MACHINES = 8192;

    /**
     * 一次写操作的结果。
     *
     * <p>不用 boolean：调用方要区分「id 重复」「机器已属于别的节点」「方块已被占用」
     * 「库满」，才能给出不同回执；混成一个 {@code false} 会把占用冲突误报成重复接入。</p>
     */
    public enum Result {
        /** 已加入。 */
        ADDED,
        /** 已替换。 */
        REPLACED,
        /** 已移除。 */
        REMOVED,
        /** 节点 id 或机器 id 已存在。 */
        DUPLICATE,
        /** 方块已经属于别的节点。 */
        CONFLICT,
        /** 超过硬上限。 */
        FULL,
        /** 要替换 / 移除的节点不存在。 */
        NOT_FOUND
    }

    private final Map<UUID, Node> byId = new LinkedHashMap<>();
    private final Map<UUID, Node> machineToNode = new HashMap<>();
    private final Map<BlockKey, Node> blockToOwner = new HashMap<>();

    /**
     * 加一个节点：校验 id、机器、方块唯一与硬上限，全部通过后一次性写入三张索引。
     *
     * @param node 节点
     * @return 加入结果；失败时索引不变
     */
    public Result add(Node node) {
        if (this.byId.containsKey(node.id())) {
            return Result.DUPLICATE;
        }
        Result invalid = checkStructure(node, null);
        if (invalid != null) {
            return invalid;
        }
        if (this.byId.size() + 1 > HARD_MAX_NODES
                || this.totalMachineCount() + node.machineCount() > HARD_MAX_TOTAL_MACHINES) {
            return Result.FULL;
        }
        this.index(node);
        return Result.ADDED;
    }

    /**
     * 替换一个已有节点：先比较旧 / 新的方块差集并校验冲突与上限，再原子替换索引。
     *
     * <p>旧节点自己的机器与方块不算冲突；新节点多出来的方块必须全局空闲。</p>
     *
     * @param node 新节点；id 必须已存在
     * @return 替换结果；失败时旧节点与索引保持不变
     */
    public Result replace(Node node) {
        Node old = this.byId.get(node.id());
        if (old == null) {
            return Result.NOT_FOUND;
        }
        Result invalid = checkStructure(node, old);
        if (invalid != null) {
            return invalid;
        }
        int machineDelta = node.machineCount() - old.machineCount();
        if (this.totalMachineCount() + machineDelta > HARD_MAX_TOTAL_MACHINES) {
            return Result.FULL;
        }
        this.unindex(old);
        this.index(node);
        return Result.REPLACED;
    }

    /**
     * 移除一个节点及其全部机器 / 方块索引。
     *
     * @param nodeId 节点 id
     * @return 移除结果
     */
    public Result remove(UUID nodeId) {
        Node old = this.byId.get(nodeId);
        if (old == null) {
            return Result.NOT_FOUND;
        }
        this.unindex(old);
        return Result.REMOVED;
    }

    /**
     * 校验结构不变量与「与其它节点」的冲突。
     *
     * @param node    待写入节点
     * @param ignored 替换时被替换的旧节点；{@code null} 表示新增
     * @return 失败原因；全部通过时为 {@code null}
     */
    @Nullable
    private Result checkStructure(Node node, @Nullable Node ignored) {
        if (node.machineCount() == 0) {
            return Result.CONFLICT;
        }
        if (node.machineCount() > HARD_MAX_MACHINES_PER_NODE) {
            return Result.FULL;
        }
        for (Machine machine : node.machines()) {
            Node owner = this.machineToNode.get(machine.id());
            if (owner != null && owner != ignored) {
                return Result.DUPLICATE;
            }
            Node blockOwner = this.blockToOwner.get(machine.block());
            if (blockOwner != null && blockOwner != ignored) {
                return Result.CONFLICT;
            }
        }
        return null;
    }

    /**
     * 把节点的机器与方块写进索引。
     *
     * @param node 节点
     */
    private void index(Node node) {
        this.byId.put(node.id(), node);
        for (Machine machine : node.machines()) {
            this.machineToNode.put(machine.id(), node);
            this.blockToOwner.put(machine.block(), node);
        }
    }

    /**
     * 从索引里摘掉节点的机器与方块。
     *
     * @param node 节点
     */
    private void unindex(Node node) {
        this.byId.remove(node.id());
        for (Machine machine : node.machines()) {
            this.machineToNode.remove(machine.id());
            this.blockToOwner.remove(machine.block());
        }
    }

    @Override
    public Optional<Node> byId(UUID id) {
        return Optional.ofNullable(this.byId.get(id));
    }

    @Override
    public Optional<Node> nodeOfMachine(UUID machineId) {
        return Optional.ofNullable(this.machineToNode.get(machineId));
    }

    @Override
    public Optional<Machine> machineAt(BlockKey key) {
        Node owner = this.blockToOwner.get(key);
        return owner == null ? Optional.empty() : owner.machineAt(key);
    }

    @Override
    public Optional<Node> nodeAt(BlockKey key) {
        return Optional.ofNullable(this.blockToOwner.get(key));
    }

    @Override
    public Optional<Node> byShortId(String shortId) {
        Node found = null;
        for (Node node : this.byId.values()) {
            if (shortId(node.id()).equalsIgnoreCase(shortId)) {
                if (found != null) {
                    return Optional.empty();
                }
                found = node;
            }
        }
        return Optional.ofNullable(found);
    }

    @Override
    public Optional<Machine> machineByShortId(String shortId) {
        Machine found = null;
        for (Node node : this.byId.values()) {
            for (Machine machine : node.machines()) {
                if (machine.shortId().equalsIgnoreCase(shortId)) {
                    if (found != null) {
                        return Optional.empty();
                    }
                    found = machine;
                }
            }
        }
        return Optional.ofNullable(found);
    }

    @Override
    public List<Node> all() {
        return List.copyOf(this.byId.values());
    }

    @Override
    public int size() {
        return this.byId.size();
    }

    @Override
    public boolean isEmpty() {
        return this.byId.isEmpty();
    }

    @Override
    public int totalMachineCount() {
        int total = 0;
        for (Node node : this.byId.values()) {
            total += node.machineCount();
        }
        return total;
    }

    /**
     * 取 id 的前 8 位。
     *
     * @param id 节点 id
     * @return 短 id
     */
    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

}
