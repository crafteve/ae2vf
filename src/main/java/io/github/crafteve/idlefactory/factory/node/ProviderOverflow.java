package io.github.crafteve.idlefactory.factory.node;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.machine.MachineProvider;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.MEStorage;
import appeng.helpers.patternprovider.PatternProviderTarget;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/**
 * 待发送余量：一份样板已经被 CPU 交过来、但机器这一刻收不下的零头。
 *
 * <p>语义照 AE2 的 sendList：只存刚接受的那一份的零头，不存整份、不存多份；队列非空时
 * {@code isBusy()} 为真，CPU 会跳过这台机器的所有样板，直到零头送完。队列随机器记录落盘，
 * 重启不丢。</p>
 *
 * <p>每 tick 先按上次的面送，面不能用了就按 key 重新选面；队列满或机器拆除时退回 ME 网络，
 * 网络也收不下才掉在机器坐标，绝不静默丢弃。</p>
 */
final class ProviderOverflow {
    private ProviderOverflow() {
        // 纯静态工具类。
    }

    /**
     * 记一条刚被接受、但没塞进机器的零头。
     *
     * @param node   机器节点
     * @param key    资源
     * @param amount 数量；不为正时忽略
     * @param face   这次用的面；{@code null} 表示面未定
     */
    static void push(MachineNode node, AEKey key, long amount, @Nullable Direction face) {
        if (amount <= 0L) {
            return;
        }
        MachineProvider provider = node.machine().provider();
        if (provider.addPending(new GenericStack(key, amount), face)) {
            node.markDataChanged();
            return;
        }
        long returned = returnToNetwork(node, key, amount);
        long left = amount - returned;
        boolean dropped = left <= 0L || dropAtMachine(node, key, left);
        node.report("余量队列已满：退回网络 " + returned + "/" + amount + " "
                + key.getDisplayName().getString() + (dropped ? "" : "，其余无处可放"));
        if (!dropped) {
            IdleFactory.LOGGER.warn("Idle Factory: 机器 [{}] 有余量无处可放：{} x{}",
                    node.machine().ref().shortId(), key.getId(), left);
        }
    }

    /**
     * 每 tick 试送一次余量。
     *
     * <p>每送走一笔就立刻把该条的剩余量写回镜像，{@code finally} 统一落库：异常时 pending
     * 必须只剩「还没送进去的部分」，否则下一轮会把已经进机器的量再送一遍。异常按 4.4 降级成
     * 一条限流日志，不再上抛，避免把同一节点本 tick 后面的样板重建与落盘一起打断。</p>
     *
     * @param node 机器节点
     * @return 是否改动了要落盘的数据
     */
    static boolean flush(MachineNode node) {
        MachineProvider provider = node.machine().provider();
        if (provider.pendingCount() == 0) {
            return false;
        }
        ProviderFaceResolver.Faces faces = node.faces();
        List<MachineProvider.Pending> entries = provider.pending();
        // 与 pending 等长的镜像：谁被送走多少就扣多少，异常时它就是真实的剩余量。
        List<MachineProvider.Pending> mirror = new ArrayList<>(entries);
        boolean insertedAny = false;
        try {
            for (int i = 0; i < entries.size(); i++) {
                MachineProvider.Pending entry = entries.get(i);
                AEKey key = entry.stack().what();
                long left = entry.stack().amount();
                Direction face = entry.face();
                if (faces != null && face != null) {
                    PatternProviderTarget target = faces.target(face);
                    long inserted = target == null ? 0L : target.insert(key, left, Actionable.MODULATE);
                    // 先扣量再走下一步：第二次 insert 抛异常时，这一笔不能留在 pending 里重送。
                    if (inserted > 0L) {
                        left -= inserted;
                        mirror.set(i, new MachineProvider.Pending(new GenericStack(key, left), face));
                    }
                    insertedAny |= inserted > 0L;
                }
                if (left > 0L && faces != null) {
                    Direction next = faces.select(key, left);
                    if (next != null) {
                        PatternProviderTarget target = faces.target(next);
                        long more = target == null ? 0L : target.insert(key, left, Actionable.MODULATE);
                        // 两条 insert 共用同一个 left / face：换面后剩余量按新面记。
                        face = next;
                        if (more > 0L) {
                            left -= more;
                        }
                        mirror.set(i, new MachineProvider.Pending(new GenericStack(key, left), face));
                        insertedAny |= more > 0L;
                    }
                }
            }
        } catch (RuntimeException e) {
            // 4.4：节点 tick 里不外抛；pending 的一致性已经在 finally 里收口。
            node.logThrottled(node.gameTime(), "余量冲刷异常：" + e);
        } finally {
            if (insertedAny) {
                provider.setPending(mirror);
                node.markDataChanged();
            }
        }
        return insertedAny;
    }

    /**
     * 拆机器时把余量全部退回网络。
     *
     * <p>机器记录马上就要被删掉，留在数据里等于凭空消失，所以这里必须清空：先按面送机器，
     * 送不进去的插网络，网络也收不下才掉在机器坐标。</p>
     *
     * @param node 机器节点
     */
    static void drain(MachineNode node) {
        flush(node);
        MachineProvider provider = node.machine().provider();
        if (provider.pendingCount() == 0) {
            return;
        }
        MEStorage network = node.networkStorage();
        List<MachineProvider.Pending> entries = provider.pending();
        int failed = 0;
        for (MachineProvider.Pending entry : entries) {
            AEKey key = entry.stack().what();
            long left = entry.stack().amount();
            if (network != null) {
                try {
                    left -= network.insert(key, left, Actionable.MODULATE, node.actionSource());
                } catch (RuntimeException e) {
                    // 按「网络没收下」处理：left 不减，交给下面的掉落兜底。这里是拆机器路径，
                    // 机器记录马上要删；异常一旦上抛，clearPending 不执行、余量就随记录消失。
                    // 网络若在已收下后才抛，这部分会重复掉一份——接口无法回读，宁可重也不能丢。
                    node.logThrottled(node.gameTime(), "拆除时退回余量异常：" + e);
                }
            }
            if (left > 0L && !dropAtMachine(node, key, left)) {
                failed++;
            }
        }
        provider.clearPending();
        node.markDataChanged();
        if (failed > 0) {
            IdleFactory.LOGGER.warn("Idle Factory: 机器 [{}] 拆除时有 {} 条余量既送不回机器也无法落物",
                    node.machine().ref().shortId(), failed);
        }
        node.report("拆除机器：退回 " + entries.size() + " 条余量"
                + (failed > 0 ? "（" + failed + " 条无处可放，已记日志）" : ""));
    }

    /**
     * 把一份退不回去的资源掉在机器坐标，作为最后的兜底。
     *
     * @param node   机器节点
     * @param key    资源
     * @param amount 数量
     * @return 是否真的掉出去了；区块没加载、数量不为正，或该资源没有掉落物形态
     *         （流体 / 能量）时返回 {@code false}，调用方必须把它记进诊断
     */
    static boolean dropAtMachine(MachineNode node, AEKey key, long amount) {
        ServerLevel level = node.machineLevel();
        BlockPos pos = node.machine().ref().pos();
        if (level == null || amount <= 0L || !level.isLoaded(pos)) {
            return false;
        }
        List<ItemStack> drops = new ArrayList<>();
        key.addDrops(amount, drops, level, pos);
        if (drops.isEmpty()) {
            // 流体 / 能量的 addDrops 是空实现，掉不出去就不能谎报成功
            return false;
        }
        for (ItemStack stack : drops) {
            Block.popResource(level, pos, stack);
        }
        return true;
    }

    /**
     * 插进 ME 网络库存；CPU 的 waitingFor 会先截获，算不算完成由 CPU 自己判断。
     *
     * @param node   机器节点
     * @param key    资源
     * @param amount 数量
     * @return 实际插入的数量
     */
    private static long returnToNetwork(MachineNode node, AEKey key, long amount) {
        MEStorage network = node.networkStorage();
        if (network == null) {
            return 0L;
        }
        return network.insert(key, amount, Actionable.MODULATE, node.actionSource());
    }
}
