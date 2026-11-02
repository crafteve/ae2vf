package io.github.crafteve.idlefactory.factory.node;

import java.util.Set;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;

import net.minecraft.core.Direction;

/**
 * 常驻产物回收：把机器上的样板产物抽出来插进 ME 网络，让 CPU 自己判定任务完成。
 *
 * <p>白名单只有样板产物，不加容器物品——输入槽里的原料不是产物，抽走会让加工停摆。
 * 节奏是「活跃 5 tick、空闲 20 → 40 → 80 → 128」：派单成功或刚抽到东西算活跃，
 * 长时间没收获就退避，避免六面能力查询把服务器 tick 吃掉。调度由 {@link NodeManager}
 * 统一分帧，本类只管一台机器。</p>
 *
 * <p>抽取前先问网络能收多少，抽出来立刻插网络；插不回去的塞回机器，机器也不要才掉在
 * 机器坐标。全程不丢东西。</p>
 */
public final class ProviderReturnSweep {
    /** 活跃期扫描间隔（tick）。 */
    static final int ACTIVE_INTERVAL = 5;
    /** 空闲退避阶梯（tick）。 */
    static final int[] IDLE_DELAYS = {20, 40, 80, 128};
    /** 一次派单 / 一次收获算活跃多久（tick）。 */
    static final long ACTIVE_WINDOW = 200L;
    /** 固定顺序的六面。 */
    private static final Direction[] FACES = Direction.values();

    private final MachineNode node;
    /** 下一次允许扫描的 tick。 */
    private long nextSweepTick;
    /** 活跃期截止 tick。 */
    private long activeUntil;
    /** 上一次真正扫过的时间。 */
    private long lastSweepTick;
    /** 上一次抽到东西的时间。 */
    private long lastExtractTick;
    /** 空闲退避走到第几级。 */
    private int idleStep;
    /** 上一轮有存储能力的面数。 */
    private int storageFaces;

    /**
     * @param node 所属机器节点
     */
    ProviderReturnSweep(MachineNode node) {
        this.node = node;
    }

    /**
     * 切回活跃节奏；派单成功、抽到东西、机器重新上线时调。
     *
     * @param now 当前游戏时间
     */
    public void activate(long now) {
        this.activeUntil = now + ACTIVE_WINDOW;
        this.idleStep = 0;
        this.nextSweepTick = now + ACTIVE_INTERVAL;
    }

    /**
     * 这一轮轮到这台机器了吗。
     *
     * @param now 当前游戏时间
     * @return 是否到期
     */
    public boolean isDue(long now) {
        return now >= this.nextSweepTick;
    }

    /**
     * 扫一轮六面：白名单里的产物能进网络就抽走。
     *
     * @param now 当前游戏时间
     */
    public void sweep(long now) {
        this.lastSweepTick = now;
        Set<AEKey> products = this.node.products();
        if (products.isEmpty() || !this.node.isAvailable()) {
            this.storageFaces = 0;
            this.idle(now);
            return;
        }
        ProviderFaceResolver.Faces faces = this.node.faces();
        MEStorage network = this.node.networkStorage();
        if (faces == null || network == null) {
            this.storageFaces = 0;
            this.idle(now);
            return;
        }
        int facesWithStorage = 0;
        boolean harvested = false;
        for (Direction face : FACES) {
            MEStorage storage = faces.storage(face);
            if (storage == null || storage == network) {
                continue;
            }
            facesWithStorage++;
            harvested |= this.sweepFace(storage, network, products);
        }
        this.storageFaces = facesWithStorage;
        if (harvested) {
            this.lastExtractTick = now;
            this.activate(now);
            return;
        }
        this.idle(now);
    }

    /** 上一轮有存储能力的面数；0 表示六面都抽不到产物。 */
    public int storageFaces() {
        return this.storageFaces;
    }

    /** 上一次扫描的时间。 */
    public long lastSweepTick() {
        return this.lastSweepTick;
    }

    /** 上一次抽到东西的时间。 */
    public long lastExtractTick() {
        return this.lastExtractTick;
    }

    /** 当前空闲退避档位。 */
    public int idleStep() {
        return this.idleStep;
    }

    /**
     * 扫一个面：白名单 key 逐条走「模拟进网络 → 从机器抽 → 插网络 → 剩下的塞回去」。
     *
     * @param storage  该面的存储
     * @param network  网络库存
     * @param products 产物白名单
     * @return 是否抽到了东西
     */
    private boolean sweepFace(MEStorage storage, MEStorage network, Set<AEKey> products) {
        IActionSource source = this.node.actionSource();
        KeyCounter available = storage.getAvailableStacks();
        boolean harvested = false;
        for (var entry : available) {
            AEKey key = entry.getKey();
            if (!products.contains(key)) {
                continue;
            }
            long amount = entry.getLongValue();
            if (amount <= 0L) {
                continue;
            }
            long accepted = network.insert(key, amount, Actionable.SIMULATE, source);
            if (accepted <= 0L) {
                continue;
            }
            long extracted = storage.extract(key, Math.min(amount, accepted),
                    Actionable.MODULATE, source);
            if (extracted <= 0L) {
                continue;
            }
            long inserted;
            try {
                inserted = network.insert(key, extracted, Actionable.MODULATE, source);
            } catch (RuntimeException e) {
                // 抽出来的量已经不在机器里：网络插入抛异常时整份按「没插进去」收回，机器收不下
                // 的再掉在机器旁。异常不能上抛——NodeManager 只留一条日志，资源两边都不在。
                this.node.logThrottled(this.node.gameTime(), "回收插入异常：" + e);
                if (!this.putBack(storage, key, extracted, source)) {
                    this.node.report("回收异常且无处可放：" + extracted + " "
                            + key.getDisplayName().getString());
                }
                // 抽动过就保持活跃节奏，网络恢复后尽快再扫。
                harvested = true;
                continue;
            }
            long leftover = extracted - inserted;
            if (leftover > 0L && !this.putBack(storage, key, leftover, source)) {
                this.node.report("回收无处可放：" + leftover + " " + key.getDisplayName().getString());
            }
            harvested = true;
        }
        return harvested;
    }

    /**
     * 把已经从机器抽出来的量塞回机器；塞不下的掉在机器旁。
     *
     * <p>这是恢复路径，本身必须不抛异常：机器侧插入再炸时按「一点没塞回去」处理，直接走
     * 掉落兜底。返回 {@code false} 表示连掉落都失败，调用方必须上报。</p>
     *
     * @param storage 该面的存储
     * @param key     资源
     * @param amount  数量
     * @param source  动作源
     * @return 是否全部安置（塞回机器或掉在机器旁）
     */
    private boolean putBack(MEStorage storage, AEKey key, long amount, IActionSource source) {
        long back;
        try {
            back = storage.insert(key, amount, Actionable.MODULATE, source);
        } catch (RuntimeException e) {
            // 机器侧也炸了就按「一点没塞回去」处理，能掉多少掉多少。
            this.node.logThrottled(this.node.gameTime(), "回收塞回异常：" + e);
            back = 0L;
        }
        long left = amount - back;
        if (left <= 0L) {
            return true;
        }
        return ProviderOverflow.dropAtMachine(this.node, key, left);
    }

    /**
     * 没收获时排下一次扫描：活跃期保持 5 tick，空闲按阶梯退避。
     *
     * @param now 当前游戏时间
     */
    private void idle(long now) {
        if (now < this.activeUntil) {
            this.nextSweepTick = now + ACTIVE_INTERVAL;
            return;
        }
        int step = Math.min(this.idleStep, IDLE_DELAYS.length - 1);
        this.nextSweepTick = now + IDLE_DELAYS[step];
        this.idleStep = Math.min(this.idleStep + 1, IDLE_DELAYS.length - 1);
    }
}
