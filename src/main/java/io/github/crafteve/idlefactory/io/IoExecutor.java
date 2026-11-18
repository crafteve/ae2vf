package io.github.crafteve.idlefactory.io;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.capability.CapabilityKind;
import io.github.crafteve.idlefactory.machine.Machine;
import io.github.crafteve.idlefactory.storage.AeKeySupport;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;

import net.minecraft.core.Direction;

/**
 * 机器侧唯一写入口：{@code move} / {@code fill} / {@code refund} / {@code sweep}。
 *
 * <p>被动与主动共用这一个写入口，区别只在两头的存储是谁：被动是「仓库 ↔ 机器」，
 * 主动是「凭空补进机器」与「产物扫回仓库」。执行器不决定搬什么、不选面——清单由规划器给，
 * 面由 {@link FacePick} 定。</p>
 *
 * <p><b>唯一不变式：搬不进目标的东西，不留在半空。</b>所有路径都先模拟再提交，提交少于
 * 扣减量时把差额退回来源；回退失败只记日志（D21），绝不生成掉落物。</p>
 */
public final class IoExecutor {
    /** 能量面探测用的模拟量：Mekanism 的 FE↔J 换算会在小数值上截断。 */
    private static final int ENERGY_PROBE = 1000;
    /** 其它资源探测的模拟量。 */
    private static final int ITEM_PROBE = 1;
    /** 固定顺序的六面。 */
    private static final Direction[] FACES = Direction.values();

    private final PortExecutor ports;

    /**
     * @param ports 端口缓存；执行器不自己建存储
     */
    public IoExecutor(PortExecutor ports) {
        this.ports = ports;
    }

    /**
     * 执行一条 Job：从 {@code network} 补进机器，或从机器抽回 {@code network}。
     *
     * <p>面在 Job 里定死；这里只负责取到那个面的存储、跑事务、把「面不对」与「满了」分开：
     * 目标这一刻一个都收不下时，如果别的面能收，就是面不对（{@link IoReason#REJECTED}），
     * 否则是满了（{@link IoReason#FULL}）。前者要清经验面，后者要保住经验面。</p>
     *
     * @param network 仓库
     * @param job     任务
     * @return 本次结果
     */
    public IoResult move(MEStorage network, IoJob job) {
        MEStorage machineStorage = this.ports.storage(job.machine(), job.face());
        if (machineStorage == null) {
            return new IoResult(0L, IoReason.NO_FACE);
        }
        boolean input = job.side() == IoSide.INPUT;
        IoResult result = input
                ? this.transfer(network, machineStorage, job.key(), job.amount())
                : this.transfer(machineStorage, network, job.key(), job.amount());
        if (result.reason() != IoReason.FULL || !input) {
            // 输出侧的 FULL 只可能是仓库满，不存在「面不对」；只有输入侧才需要再探一次。
            return result;
        }
        // 目标一个都收不下：面确实不对时别的面能收。多探一次是为了把 REJECTED 与 FULL 分开，
        // 只有零结果路径会付出这个代价。
        return this.otherFaceAccepts(job) ? new IoResult(0L, IoReason.REJECTED) : result;
    }

    /**
     * 一次搬运事务：源 → 目标。
     *
     * <p>顺序固定：先问目标能收多少 → 按这个量从源扣 → 提交 → 差额退回源。先扣再提交会让
     * 「目标其实不收」变成凭空消失；先提交再扣会让「源其实没料」变成凭空生成。</p>
     *
     * @param source 来源
     * @param target 目标
     * @param key    资源
     * @param amount 想搬多少；{@code <= 0} 表示没有量
     * @return 实际搬动量与原因
     */
    public IoResult transfer(MEStorage source, MEStorage target, AEKey key, long amount) {
        if (amount <= 0L) {
            return IoResult.NOTHING;
        }
        long accepted;
        try {
            accepted = target.insert(key, amount, Actionable.SIMULATE, AeKeySupport.SOURCE);
        } catch (RuntimeException e) {
            this.log("模拟写入目标异常", key, e);
            return new IoResult(0L, IoReason.ERROR);
        }
        if (accepted <= 0L) {
            return new IoResult(0L, IoReason.FULL);
        }
        long available;
        try {
            available = source.extract(key, Math.min(amount, accepted), Actionable.SIMULATE,
                    AeKeySupport.SOURCE);
        } catch (RuntimeException e) {
            this.log("模拟从来源抽取异常", key, e);
            return new IoResult(0L, IoReason.ERROR);
        }
        if (available <= 0L) {
            return new IoResult(0L, IoReason.NO_MATERIAL);
        }
        long want = Math.min(available, accepted);
        long taken;
        try {
            taken = source.extract(key, want, Actionable.MODULATE, AeKeySupport.SOURCE);
        } catch (RuntimeException e) {
            // 取没取出来是未知的：按「一点没取」处理，下一轮重试；宁可重复也不让料悬空。
            this.log("从来源抽取异常", key, e);
            return new IoResult(0L, IoReason.ERROR);
        }
        if (taken <= 0L) {
            return new IoResult(0L, IoReason.NO_MATERIAL);
        }
        long inserted;
        try {
            inserted = target.insert(key, taken, Actionable.MODULATE, AeKeySupport.SOURCE);
        } catch (RuntimeException e) {
            // 写进去多少未知：把本次从源扣走的整笔退回去（可能重复），记 ERROR，不让单卡死。
            this.log("提交写入目标异常", key, e);
            this.refund(source, key, taken);
            return new IoResult(0L, IoReason.ERROR);
        }
        long refund = taken - inserted;
        if (refund > 0L) {
            this.refund(source, key, refund);
        }
        return new IoResult(inserted, inserted > 0L ? IoReason.RUN : IoReason.FULL);
    }

    /**
     * 凭空补：材料在派单时已经从网络抽走，这里逐行直接写，不回退也不模拟。
     *
     * <p>{@code perLine} 已按行聚合（同一行只出现一次，量 = 每份用量 × 份数），所以一张
     * 81 行的样板就是 81 次 {@code insert}，与份数无关。部分成功是正常路径：返回值就是
     * 机器实际收下的量，账本按它累加。</p>
     *
     * @param machine 机器侧的存储
     * @param perLine 资源 → 要写的总量
     * @return 逐行结果
     */
    public Map<AEKey, IoResult> fill(MEStorage machine, Map<AEKey, Long> perLine) {
        Map<AEKey, IoResult> results = new LinkedHashMap<>();
        for (Map.Entry<AEKey, Long> line : perLine.entrySet()) {
            long amount = line.getValue();
            if (amount <= 0L) {
                continue;
            }
            long inserted;
            try {
                inserted = machine.insert(line.getKey(), amount, Actionable.MODULATE,
                        AeKeySupport.SOURCE);
            } catch (RuntimeException e) {
                // 这一行写进去多少未知：剩余量留队下一 tick 重试（可能重复，但单不卡死）。
                this.log("补料写入机器异常", line.getKey(), e);
                results.put(line.getKey(), new IoResult(0L, IoReason.ERROR));
                continue;
            }
            results.put(line.getKey(), new IoResult(inserted,
                    inserted > 0L ? IoReason.RUN : IoReason.FULL));
        }
        return results;
    }

    /**
     * 把没交出去的量退回仓库；仓库不收时记日志丢弃，不生成掉落物。
     *
     * @param network 仓库
     * @param key     资源
     * @param amount  数量
     * @return 退回去多少
     */
    public IoResult refund(MEStorage network, AEKey key, long amount) {
        if (amount <= 0L) {
            return IoResult.NOTHING;
        }
        long inserted;
        try {
            inserted = network.insert(key, amount, Actionable.MODULATE, AeKeySupport.SOURCE);
        } catch (RuntimeException e) {
            this.log("退回仓库异常", key, e);
            inserted = 0L;
        }
        long lost = amount - inserted;
        if (lost > 0L) {
            // 退回失败按 D21 记日志丢弃：不生成掉落物，也不把错误藏起来。
            IdleFactory.LOGGER.warn("Idle Factory: 退回仓库失败，{} × {} 已按丢弃处理",
                    lost, AeKeySupport.displayName(key));
        }
        return new IoResult(inserted, inserted > 0L ? IoReason.RUN : IoReason.FULL);
    }

    /**
     * 回收：逐面取机器存储、抽白名单产物、按网络能收多少插入。
     *
     * @param machine  机器
     * @param network  仓库
     * @param products 产物白名单
     * @return 逐资源的回收结果；没抽动任何东西时为空表
     */
    public Map<AEKey, IoResult> sweep(Machine machine, MEStorage network, Set<AEKey> products) {
        Map<AEKey, IoResult> results = new LinkedHashMap<>();
        if (products.isEmpty()) {
            return results;
        }
        for (Direction face : FACES) {
            MEStorage storage = this.ports.storage(machine, face);
            if (storage == null || storage == network) {
                continue;
            }
            Map<AEKey, Long> available;
            try {
                available = AeKeySupport.snapshot(storage);
            } catch (RuntimeException e) {
                // 某一面读不出来：只失效这一面，其它面继续扫。
                this.log("读取机器面库存异常", null, e);
                this.ports.invalidate(machine, face);
                continue;
            }
            for (Map.Entry<AEKey, Long> entry : available.entrySet()) {
                AEKey key = entry.getKey();
                long count = entry.getValue();
                if (count <= 0L || !products.contains(key)) {
                    continue;
                }
                IoResult result = this.transfer(storage, network, key, count);
                results.merge(key, result, IoExecutor::worse);
                if (result.reason() == IoReason.ERROR) {
                    this.ports.invalidate(machine, face);
                }
            }
        }
        return results;
    }

    /**
     * 这台机器在这个面之外，是否还有面能收下这个资源。
     *
     * @param job 任务
     * @return 其它面能收时为 {@code true}
     */
    private boolean otherFaceAccepts(IoJob job) {
        Machine machine = job.machine();
        long probe = AeKeySupport.kind(job.key()) == CapabilityKind.ENERGY ? ENERGY_PROBE : ITEM_PROBE;
        for (Direction face : FACES) {
            if (face == job.face()) {
                continue;
            }
            MEStorage storage = this.ports.storage(machine, face);
            if (storage == null) {
                continue;
            }
            try {
                long accepted = job.side() == IoSide.INPUT
                        ? storage.insert(job.key(), probe, Actionable.SIMULATE, AeKeySupport.SOURCE)
                        : storage.extract(job.key(), probe, Actionable.SIMULATE, AeKeySupport.SOURCE);
                if (accepted > 0L) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // 这一面不可用，继续看下一个
            }
        }
        return false;
    }

    /** 两条结果取更严重的那条，数量相加。 */
    private static IoResult worse(IoResult left, IoResult right) {
        return new IoResult(left.moved() + right.moved(),
                right.reason().severity() > left.reason().severity() ? right.reason() : left.reason());
    }

    private void log(String message, @Nullable AEKey key, RuntimeException cause) {
        IdleFactory.LOGGER.warn("Idle Factory: {}（{}）", message,
                key == null ? "未知资源" : AeKeySupport.describe(key), cause);
    }
}
