package io.github.crafteve.idlefactory.client.wand;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.crafteve.idlefactory.location.BlockKey;
import io.github.crafteve.idlefactory.network.payload.WandLinksRequestC2S;
import io.github.crafteve.idlefactory.network.payload.WandLinksS2C;

import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 客户端链接快照：服务端 {@link WandLinksS2C} 的唯一缓存。
 *
 * <p>服务端每次结构变更都会推送整份快照，客户端整体替换，不累积、不合并。缓存同时维护三张
 * 索引：{@code BlockKey → 归属} 与 {@code machineId → 方块} 供草稿三态、红框与面板用，
 * {@code nodeId → 节点} 供终端编辑与类型基准用；另外把机器方块摊平成一份列表，渲染每帧只
 * 读它，不重新遍历节点。</p>
 *
 * <p>只读缓存，不参与业务判断；登录 / 换维度 / 打开终端时主动请求一次，之后靠推送更新。</p>
 */
public final class WandLinkCache {
    private static List<BlockKey> machineBlocks = List.of();
    private static Map<BlockKey, Owner> owners = Map.of();
    private static Map<UUID, BlockKey> machineBlocksById = Map.of();
    private static Map<UUID, WandLinksS2C.NodeLink> nodes = Map.of();

    private WandLinkCache() {
        // 纯静态缓存
    }

    /**
     * 一个方块属于哪个节点、哪台机器。
     *
     * @param nodeId    节点 id
     * @param machineId 机器 id
     */
    public record Owner(UUID nodeId, UUID machineId) {
    }

    /**
     * 用服务端快照整体替换缓存。
     *
     * @param payload 同步包
     */
    public static void update(WandLinksS2C payload) {
        Map<BlockKey, Owner> nextOwners = new HashMap<>();
        Map<UUID, BlockKey> nextMachineBlocks = new HashMap<>();
        Map<UUID, WandLinksS2C.NodeLink> nextNodes = new HashMap<>();
        List<BlockKey> nextBlocks = new ArrayList<>();
        for (WandLinksS2C.NodeLink node : payload.nodes()) {
            nextNodes.put(node.nodeId(), node);
            for (WandLinksS2C.MachineLink machine : node.machines()) {
                nextOwners.put(machine.block(),
                        new Owner(node.nodeId(), machine.machineId()));
                nextMachineBlocks.put(machine.machineId(), machine.block());
                nextBlocks.add(machine.block());
            }
        }
        owners = Map.copyOf(nextOwners);
        machineBlocksById = Map.copyOf(nextMachineBlocks);
        nodes = Map.copyOf(nextNodes);
        machineBlocks = List.copyOf(nextBlocks);
    }

    /** 清空缓存；登录 / 登出 / 换维度时调。 */
    public static void clear() {
        machineBlocks = List.of();
        owners = Map.of();
        machineBlocksById = Map.of();
        nodes = Map.of();
    }

    /** 全部已绑定机器方块，按节点与成员顺序摊平。 */
    public static List<BlockKey> boundBlocks() {
        return machineBlocks;
    }

    /**
     * 方块归属。
     *
     * @param block 方块
     * @return 归属；未绑定时为空
     */
    public static Optional<Owner> owner(BlockKey block) {
        return Optional.ofNullable(owners.get(block));
    }

    /**
     * 按机器 id 取它所在的方块。
     *
     * @param machineId 机器 id
     * @return 方块；机器不在快照里时为空
     */
    public static Optional<BlockKey> blockOfMachine(UUID machineId) {
        return Optional.ofNullable(machineBlocksById.get(machineId));
    }

    /**
     * 按节点 id 取快照节点。
     *
     * @param nodeId 节点 id
     * @return 节点；没有该节点时为空
     */
    public static Optional<WandLinksS2C.NodeLink> node(UUID nodeId) {
        return Optional.ofNullable(nodes.get(nodeId));
    }

    /** 主动请求一份全量快照；登录、换维度、打开终端时调。 */
    public static void request() {
        PacketDistributor.sendToServer(new WandLinksRequestC2S());
    }
}
