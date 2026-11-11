package io.github.crafteve.idlefactory.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryRuntime;
import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder;
import io.github.crafteve.idlefactory.factory.NodeStatus;
import io.github.crafteve.idlefactory.factory.NodeStatusResolver;
import io.github.crafteve.idlefactory.network.payload.FactoryTableS2C;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 工厂整表的唯一发送出口。
 *
 * <p>请求路径和“编辑动作完成后立即刷新”共用一个实现，避免两处各自构建视图、漏字段或排序不同。
 * 读路径不探世界：机器状态全部来自 {@link FactorySnapshotBuilder} 对观测缓存的读取。</p>
 *
 * <p>P1 删除产线后这里只发机器表，不再附带产线列表。</p>
 */
public final class FactoryTableSnapshots {
    private FactoryTableSnapshots() {
        // 纯静态工具类
    }

    /**
     * 立即把当前工厂的机器表发给玩家。
     *
     * @param player      目标玩家
     * @param containerId 菜单容器 id；过期界面自行忽略
     */
    public static void send(ServerPlayer player, int containerId) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            PacketDistributor.sendToPlayer(player, FactoryTableS2C.empty(containerId));
            return;
        }
        Optional<Factory> factory = runtime.manager().byOwner(player.getUUID());
        if (factory.isEmpty()) {
            PacketDistributor.sendToPlayer(player, FactoryTableS2C.empty(containerId));
            return;
        }
        Factory owner = factory.get();
        long now = player.serverLevel().getGameTime();
        List<FactorySnapshotBuilder.MachineView> machines =
                FactorySnapshotBuilder.machines(owner, runtime.observations(), now);
        // 节点状态只在这里算一次：被动 IO 终端直接读，不再拿第一台机器自己拼。
        List<FactoryTableS2C.NodeStatusEntry> nodeStatuses = new ArrayList<>();
        for (Map.Entry<UUID, NodeStatus> entry : NodeStatusResolver.byNode(machines).entrySet()) {
            nodeStatuses.add(new FactoryTableS2C.NodeStatusEntry(entry.getKey(), entry.getValue()));
        }
        PacketDistributor.sendToPlayer(player, new FactoryTableS2C(
                containerId, true, owner.live().storageOnline(), machines, nodeStatuses));
    }
}