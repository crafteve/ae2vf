package io.github.crafteve.idlefactory.network;

import java.util.List;
import java.util.Optional;

import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.view.NodeSnapshot;
import io.github.crafteve.idlefactory.factory.FactoryNodeSnapshotBuilder;
import io.github.crafteve.idlefactory.factory.FactoryLifecycle;
import io.github.crafteve.idlefactory.network.payload.FactoryNodeTableS2C;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 节点表的唯一发送出口。
 *
 * <p>请求路径与动作完成后的立即刷新共用一份实现，避免两处各自构建视图、漏字段或排序不同。
 * 读路径不探世界：节点状态全部来自观测缓存。</p>
 */
public final class FactoryNodeSnapshots {
    private FactoryNodeSnapshots() {
        // 纯静态工具类
    }

    /**
     * 立即把当前工厂的节点表发给玩家。
     *
     * @param player      目标玩家
     * @param containerId 菜单容器 id；过期界面自行忽略
     */
    public static void send(ServerPlayer player, int containerId) {
        FactoryLifecycle runtime = FactoryLifecycle.get();
        if (runtime == null) {
            PacketDistributor.sendToPlayer(player, FactoryNodeTableS2C.empty(containerId));
            return;
        }
        Optional<Factory> factory = runtime.manager().byOwner(player.getUUID());
        if (factory.isEmpty()) {
            PacketDistributor.sendToPlayer(player, FactoryNodeTableS2C.empty(containerId));
            return;
        }
        Factory owner = factory.get();
        long now = player.serverLevel().getGameTime();
        List<NodeSnapshot> nodes = FactoryNodeSnapshotBuilder.nodes(
                owner, runtime.observations(), runtime.formFor(owner), runtime.nodeManager(), now);
        PacketDistributor.sendToPlayer(player, new FactoryNodeTableS2C(
                containerId, true, owner.storageOnline(), nodes));
    }
}
