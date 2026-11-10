package io.github.crafteve.idlefactory.client.wand;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.crafteve.idlefactory.factory.location.BlockKey;
import io.github.crafteve.idlefactory.factory.storage.StorageMode;
import io.github.crafteve.idlefactory.network.payload.FactoryLinksRequestC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryLinksS2C;

import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 客户端链接快照：服务端 {@link FactoryLinksS2C} 的唯一缓存。
 *
 * <p>服务端每次结构变更都会推送整份快照，客户端整体替换，不累积、不合并。缓存同时维护两张
 * 索引：{@code BlockKey → 归属} 供草稿三态与渲染用，{@code nodeId → 节点} 供终端编辑与类型
 * 基准用；另外把机器方块摊平成一份列表，渲染每帧只读它，不重新遍历节点。</p>
 *
 * <p>只读缓存，不参与业务判断；登录 / 换维度 / 打开终端时主动请求一次，之后靠推送更新。</p>
 */
public final class WandLinks {
    private static StorageMode mode = StorageMode.ME;
    private static List<FactoryLinksS2C.Link> storage = List.of();
    private static List<BlockKey> machineBlocks = List.of();
    private static Map<BlockKey, Owner> owners = Map.of();
    private static Map<UUID, FactoryLinksS2C.NodeLink> nodes = Map.of();
    private static boolean loaded;

    private WandLinks() {
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
    public static void update(FactoryLinksS2C payload) {
        Map<BlockKey, Owner> nextOwners = new HashMap<>();
        Map<UUID, FactoryLinksS2C.NodeLink> nextNodes = new HashMap<>();
        List<BlockKey> nextBlocks = new ArrayList<>();
        for (FactoryLinksS2C.NodeLink node : payload.nodes()) {
            nextNodes.put(node.nodeId(), node);
            for (FactoryLinksS2C.MachineLink machine : node.machines()) {
                nextOwners.put(machine.block(),
                        new Owner(node.nodeId(), machine.machineId()));
                nextBlocks.add(machine.block());
            }
        }
        mode = payload.mode();
        storage = payload.storage();
        owners = Map.copyOf(nextOwners);
        nodes = Map.copyOf(nextNodes);
        machineBlocks = List.copyOf(nextBlocks);
        loaded = true;
    }

    /** 清空缓存；登录 / 登出 / 换维度时调。 */
    public static void clear() {
        mode = StorageMode.ME;
        storage = List.of();
        machineBlocks = List.of();
        owners = Map.of();
        nodes = Map.of();
        loaded = false;
    }

    /** 是否已经收到过至少一份快照。 */
    public static boolean loaded() {
        return loaded;
    }

    /** 当前仓库模式。 */
    public static StorageMode mode() {
        return mode;
    }

    /** 仓库坐标（ME 一个 / 容器多个）。 */
    public static List<FactoryLinksS2C.Link> storage() {
        return storage;
    }

    /** 全部已绑定机器方块，按节点与成员顺序摊平。 */
    public static List<BlockKey> machineBlocks() {
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
     * 按节点 id 取快照节点。
     *
     * @param nodeId 节点 id
     * @return 节点；没有该节点时为空
     */
    public static Optional<FactoryLinksS2C.NodeLink> node(UUID nodeId) {
        return Optional.ofNullable(nodes.get(nodeId));
    }

    /** 主动请求一份全量快照；登录、换维度、打开终端时调。 */
    public static void request() {
        PacketDistributor.sendToServer(new FactoryLinksRequestC2S());
    }
}
