package io.github.crafteve.idlefactory.factory.node;

import java.util.Map;
import java.util.Optional;

import io.github.crafteve.idlefactory.factory.location.BlockKey;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.probe.BlockObservation;
import io.github.crafteve.idlefactory.factory.probe.NodeHealth;

import net.minecraft.resources.ResourceLocation;

/**
 * 节点类型的「全离线自动迁移」判定：纯函数，只回答「要不要换类型」。
 *
 * <p>成立条件全部满足才迁移：</p>
 * <ol>
 *   <li>节点所有机器都离线（只要还有一台在线就不认原地换的新方块）；</li>
 *   <li>所有机器的方块都探测过且<b>不是</b>过期快照；</li>
 *   <li>这些类型方块都 PRESENT、blockId 非空；</li>
 *   <li>它们都是<b>同一个</b>新 id，且不等于当前 {@code typeBlockId}。</li>
 * </ol>
 *
 * <p>部分换、换得不一样、类型方块是空气、区块未加载、健康过期都不迁移。节点 id、机器 id、
 * 规则、样板、名字全部保留。写入由 {@code FactoryObservationRunner} 唯一负责。</p>
 */
public final class NodeTypeResolver {
    private NodeTypeResolver() {
        // 纯函数工具类
    }

    /**
     * 判断是否需要迁移类型。
     *
     * @param node             节点
     * @param observations     方块观测快照
     * @param now              当前游戏时间
     * @param staleHealthTicks 过期阈值（tick）
     * @return 新类型方块 id；不满足迁移条件时为空
     */
    public static Optional<ResourceLocation> migrationTarget(Node node,
            Map<BlockKey, BlockObservation> observations, long now, long staleHealthTicks) {
        NodeHealthResolver.Snapshot snapshot =
                NodeHealthResolver.resolve(node, observations, now, staleHealthTicks);
        if (snapshot.health() != NodeHealth.OFFLINE) {
            return Optional.empty();
        }
        ResourceLocation candidate = null;
        for (Machine machine : node.machines()) {
            BlockObservation observation = observations.get(machine.block());
            if (observation == null || !observation.probed()
                    || observation.isStale(now, staleHealthTicks)
                    || !observation.present() || observation.blockId() == null) {
                return Optional.empty();
            }
            if (candidate == null) {
                candidate = observation.blockId();
            } else if (!candidate.equals(observation.blockId())) {
                // 各机器的类型方块换成了不同 id：不是一次统一的替换，不认。
                return Optional.empty();
            }
        }
        if (candidate == null || candidate.equals(node.typeBlockId())) {
            return Optional.empty();
        }
        return Optional.of(candidate);
    }
}
