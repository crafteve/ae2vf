package io.github.crafteve.idlefactory.command;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryLifecycle;
import io.github.crafteve.idlefactory.location.BlockKey;
import io.github.crafteve.idlefactory.machine.MatchMode;
import io.github.crafteve.idlefactory.io.IoSide;
import io.github.crafteve.idlefactory.observation.ObservationReader;

/**
 * 自动补 FE 规则的延迟重试队列。
 *
 * <p>刚接入的方块在观测表里还没有 FE 能力这一条，{@code energyCapable} 只能读回 {@code false}，
 * 于是 FE 规则被静默跳过。这里把「待补 FE 规则」的机器排进队列，等观测探到 FE 能力后再补，
 * 最多等 {@link #MAX_WAIT_TICKS} tick；不写档，纯运行时状态。</p>
 */
public final class EnergyRuleRetry {
    /** 最多等多少 tick。 */
    private static final int MAX_WAIT_TICKS = 200;

    /** 一条待补规则。 */
    private record Pending(UUID factoryId, UUID nodeId, BlockKey block, long expireAt) {
    }

    private static final List<Pending> PENDING = new ArrayList<>();

    private EnergyRuleRetry() {
        // 纯静态队列
    }

    /**
     * 排一条待补 FE 规则；同一节点同一方块只排一次。
     *
     * @param factory 工厂
     * @param nodeId  节点 id
     * @param block   机器方块
     * @param now     当前游戏时间
     */
    public static void schedule(Factory factory, UUID nodeId, BlockKey block, long now) {
        for (Pending pending : PENDING) {
            if (pending.nodeId().equals(nodeId) && pending.block().equals(block)) {
                return;
            }
        }
        PENDING.add(new Pending(factory.id(), nodeId, block, now + MAX_WAIT_TICKS));
    }

    /**
     * 每 tick 收口一次：观测已经探到 FE 能力就补规则。
     *
     * @param runtime 服务端运行时
     */
    public static void flush(FactoryLifecycle runtime) {
        if (PENDING.isEmpty()) {
            return;
        }
        ObservationReader observations = runtime.observations();
        if (observations == null) {
            PENDING.clear();
            return;
        }
        long now = runtime.server().overworld() == null
                ? 0L : runtime.server().overworld().getGameTime();
        Iterator<Pending> it = PENDING.iterator();
        while (it.hasNext()) {
            Pending pending = it.next();
            var factory = runtime.manager().byId(pending.factoryId());
            if (factory.isEmpty()) {
                it.remove();
                continue;
            }
            if (observations.energyCapable(pending.block())
                    || factory.get().config().nodes().byId(pending.nodeId()).isEmpty()) {
                if (factory.get().config().nodes().byId(pending.nodeId()).isPresent()
                        && observations.energyCapable(pending.block())) {
                    CommandResult result = CommandApply.addRule(factory.get(), pending.nodeId(),
                            new FactoryCommand.Rule(IoSide.INPUT, io.github.crafteve.idlefactory.resource.AeKeyAdapter.energy(),
                                    null, MatchMode.EXACT, null));
                    if (!result.ok()) {
                        IdleFactory.LOGGER.debug("自动补 FE 规则仍未写入：{}", result.reason());
                    }
                }
                it.remove();
                continue;
            }
            if (now >= pending.expireAt()) {
                IdleFactory.LOGGER.debug("自动补 FE 规则超时：node={} block={}",
                        pending.nodeId(), pending.block());
                it.remove();
            }
        }
    }

    /** 停服时清空。 */
    public static void clear() {
        PENDING.clear();
    }
}
