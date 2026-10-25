package io.github.crafteve.idlefactory.integration.ae2;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.storage.StorageAnchor;

import appeng.api.features.Locatables;
import appeng.api.ids.AEComponents;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.core.definitions.AEItems;
import appeng.me.cluster.implementations.QuantumCluster;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * AE2 绑定层：量子纠缠奇点 → ME 网络。
 *
 * <p>奇点自带一个 {@code long} 频率（{@link AEComponents#ENTANGLED_SINGULARITY_ID}）。量子环在
 * {@link Locatables#quantumNetworkBridges()} 里按 {@code ±freq} 注册，所以两个方向都要试一次。
 * 任何一步失败都返回 {@code null}，由调用方降级成「网络丢失」。</p>
 */
public final class Ae2Binder {
    private Ae2Binder() {
        // 纯静态工具类
    }

    /** 是否是我们认识的量子纠缠奇点。 */
    public static boolean isSingularity(ItemStack stack) {
        return !stack.isEmpty() && AEItems.QUANTUM_ENTANGLED_SINGULARITY.is(stack);
    }

    /**
     * 把奇点解析成它所在 ME 网络的 {@link IGrid}。
     *
     * @param singularity 奇点物品（调用方保证非空）
     * @param level       服务端世界
     * @return 绑定到的网络；奇点无效、频率为 0、环不在线或节点不可用时返回 {@code null}
     */
    @Nullable
    public static IGrid bind(ItemStack singularity, ServerLevel level) {
        if (!isSingularity(singularity)) {
            return null;
        }
        long frequency = singularity.getOrDefault(AEComponents.ENTANGLED_SINGULARITY_ID, 0L);
        if (frequency == 0L) {
            return null;
        }
        IGridNode node = node(level, frequency);
        if (node == null) {
            return null;
        }
        try {
            return node.getGrid();
        } catch (RuntimeException e) {
            // 节点已销毁
            return null;
        }
    }

    /**
     * 按频率找量子环的可操作节点。
     *
     * @param level     服务端世界（用来查维度内的注册表）
     * @param frequency 奇点频率
     * @return 节点；环不在线或集群正在拆分时返回 {@code null}
     */
    @Nullable
    public static IGridNode node(ServerLevel level, long frequency) {
        QuantumCluster cluster = findCluster(level, frequency);
        if (cluster == null && frequency != Long.MIN_VALUE) {
            // AE2 侧 A 环注册 freq、B 环注册 -freq，奇点可能拿到任意一侧
            cluster = findCluster(level, -frequency);
        }
        if (cluster == null) {
            return null;
        }
        try {
            return cluster.getActionableNode();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 把频率解析成工厂可持久化的仓库地址。
     *
     * <p>环坐标取集群中心；拿不到中心时退回包围盒最小角。坐标只用于强制加载环所在区块，
     * 不影响后续按频率查找。</p>
     *
     * @param level     服务端世界
     * @param frequency 奇点频率
     * @return 仓库地址；环不在线时返回 {@code null}
     */
    @Nullable
    public static StorageAnchor anchor(ServerLevel level, long frequency) {
        QuantumCluster cluster = findCluster(level, frequency);
        if (cluster == null && frequency != Long.MIN_VALUE) {
            cluster = findCluster(level, -frequency);
        }
        if (cluster == null) {
            return null;
        }
        BlockPos ringPos;
        try {
            var center = cluster.getCenter();
            ringPos = center != null ? center.getBlockPos() : cluster.getBoundsMin();
        } catch (RuntimeException e) {
            ringPos = cluster.getBoundsMin();
        }
        return new StorageAnchor(frequency, level.dimension(), ringPos);
    }

    @Nullable
    private static QuantumCluster findCluster(ServerLevel level, long frequency) {
        try {
            IActionHost host = Locatables.quantumNetworkBridges().get(level, frequency);
            return host instanceof QuantumCluster cluster ? cluster : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
