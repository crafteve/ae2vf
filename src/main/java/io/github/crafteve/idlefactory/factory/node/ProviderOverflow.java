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
        List<MachineProvider.Pending> remaining = new ArrayList<>(entries.size());
        boolean insertedAny = false;
        for (MachineProvider.Pending entry : entries) {
            AEKey key = entry.stack().what();
            long left = entry.stack().amount();
            Direction face = entry.face();
            long inserted = 0L;
            if (faces != null && face != null) {
                PatternProviderTarget target = faces.target(face);
                inserted = target == null ? 0L : target.insert(key, left, Actionable.MODULATE);
                left -= inserted;
            }
            if (left > 0L && faces != null) {
                Direction next = faces.select(key, left);
                if (next != null) {
                    PatternProviderTarget target = faces.target(next);
                    long more = target == null ? 0L : target.insert(key, left, Actionable.MODULATE);
                    inserted += more;
                    left -= more;
                    face = next;
                }
            }
            if (left > 0L) {
                remaining.add(new MachineProvider.Pending(new GenericStack(key, left), face));
            }
            insertedAny |= inserted > 0L;
        }
        if (insertedAny) {
            provider.setPending(remaining);
            node.markDataChanged();
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
                left -= network.insert(key, left, Actionable.MODULATE, node.actionSource());
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
     * @return 是否真的掉出去了；区块没加载或数量不为正时返回 {@code false}
     */
    static boolean dropAtMachine(MachineNode node, AEKey key, long amount) {
        ServerLevel level = node.machineLevel();
        BlockPos pos = node.machine().ref().pos();
        if (level == null || amount <= 0L || !level.isLoaded(pos)) {
            return false;
        }
        List<ItemStack> drops = new ArrayList<>();
        key.addDrops(amount, drops, level, pos);
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
