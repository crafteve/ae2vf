package io.github.crafteve.idlefactory.node;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.crafteve.idlefactory.key.BlockKey;
import io.github.crafteve.idlefactory.machine.Machine;

/**
 * 节点库的只读视图：工厂配置对外只暴露这一层。
 *
 * <p>写入口只有 {@code Factory#addNode / removeNode / replaceNode} 与读档时的 {@code Factory#restoreNode}；
 * 调用方拿不到 {@link NodeLibrary} 本身，也就不会出现「改了节点库忘记落盘」的漏法。</p>
 */
public interface NodeLibraryView {
    /**
     * 按节点 id 查。
     *
     * @param id 节点 id
     * @return 节点；不在库里时为空
     */
    Optional<Node> byId(UUID id);

    /**
     * 按机器 id 找所属节点。
     *
     * @param machineId 机器 id
     * @return 节点；机器不属于任何节点时为空
     */
    Optional<Node> nodeOfMachine(UUID machineId);

    /**
     * 按方块位置找所属机器。
     *
     * @param key 方块位置
     * @return 机器；方块不属于任何机器时为空
     */
    Optional<Machine> machineAt(BlockKey key);

    /**
     * 按方块位置找所属节点。
     *
     * @param key 方块位置
     * @return 节点；方块不属于任何节点时为空
     */
    Optional<Node> nodeAt(BlockKey key);

    /**
     * 按短 id 找节点。
     *
     * @param shortId 短 id（前 8 位）
     * @return 节点；找不到或前缀有歧义时为空
     */
    Optional<Node> byShortId(String shortId);

    /**
     * 按短 id 找机器。
     *
     * @param shortId 短 id（前 8 位）
     * @return 机器；找不到或前缀有歧义时为空
     */
    Optional<Machine> machineByShortId(String shortId);

    /**
     * 所有节点（登记顺序）。
     *
     * @return 只读列表
     */
    List<Node> all();

    /** @return 节点数量 */
    int size();

    /** @return 是否为空 */
    boolean isEmpty();

    /** @return 全工厂机器总数 */
    int totalMachineCount();

}
