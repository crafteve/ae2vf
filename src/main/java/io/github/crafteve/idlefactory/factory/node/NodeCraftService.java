package io.github.crafteve.idlefactory.factory.node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.io.CapacityPick;
import io.github.crafteve.idlefactory.factory.io.FacePick;
import io.github.crafteve.idlefactory.factory.io.IoSide;
import io.github.crafteve.idlefactory.factory.io.PortExecutor;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.machine.ResourceSpec;
import io.github.crafteve.idlefactory.factory.storage.AeKeySupport;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.core.definitions.AEItems;

import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 机器的虚拟样板供应器：把机器上的样板广告给 CPU，并接住 CPU 派来的一份输入。
 *
 * <p>这是挂到虚拟节点上的 {@link ICraftingProvider} 服务，生命周期跟着节点走。AE2 的节点服务
 * 只能在 {@code create} 之前挂，所以它常驻，样板为空时就是「没有任何样板的供应器」。</p>
 *
 * <p>样板解码结果、输入的 key 集合（阻挡判定用）与产物 key 集合（回收白名单用）都缓存在这里，
 * 只在样板变化或机器上下线时重建；CPU 的合成规划直接读缓存，不会每 tick 解码样板。</p>
 *
 * <p>P2 起机器侧统一走 AE2 的 {@link MEStorage}：选面用 {@link FacePick}，容量用
 * {@link CapacityPick}，取存储用工厂共享的 {@link PortExecutor}，不再有自己的一套适配器。</p>
 */
final class NodeCraftService implements ICraftingProvider {
    /** 固定顺序的六面。 */
    private static final Direction[] FACES = Direction.values();

    private final NodeRuntime node;
    /** 当前能用的样板；机器方块不在时对外仍报空。 */
    private List<IPatternDetails> patterns = List.of();
    /** 所有样板输入的并集，已去掉组件（AE2 的阻挡判定按这个比较）。 */
    private Set<AEKey> patternInputs = Set.of();
    /** 所有样板产物的并集，回收白名单只有这些。 */
    private Set<AEKey> products = Set.of();
    /**
     * 智能倍率的容量缓存：样板 → 探测当时的可装份数。
     *
     * <p>键是样板本身，值是「节点在线机器合计能装几份」的上限，不是实时空位；机器逐渐变满后这个
     * 值可能偏大，收不下的部分由投递按整数份处理，不做每次 push 重探测。样板增删、机器上下线、
     * 倍率模式切换时整体作废。</p>
     */
    private final Map<ItemStack, Integer> smartCache = new HashMap<>();
    /** 缓存对应的节点拓扑版本；结构变化后整体作废。 */
    private long smartCacheRevision = -1L;
    /** 缓存对应的倍率模式；切模式后整体作废。 */
    @Nullable
    private MultiplierMode smartCacheMode;

    /**
     * @param node 所属机器节点
     */
    NodeCraftService(NodeRuntime node) {
        this.node = node;
    }

    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        // 被动模式只是「样板存着但不参与合成」：节点保留，样板访问终端仍能上传，只是 CPU 看不到。
        return this.node.isAvailable() && this.node.node().provider().mode() == ProviderMode.ACTIVE
                ? this.patterns : List.of();
    }

    @Override
    public boolean pushPattern(IPatternDetails details, KeyCounter[] inputHolder) {
        NodeProvider provider = this.node.node().provider();
        if (this.isBusy() || !this.node.isAvailable() || !this.node.isNodeOnline()) {
            return false;
        }
        if (!this.patterns.contains(details) || !details.supportsPushInputsToExternalInventory()) {
            return false;
        }
        // 一次派单只挑一台在线机器，整份货给它；不拆分到别的机器（多机分发在 P3）。
        Machine machine = this.node.pickMachine();
        if (machine == null) {
            return false;
        }
        if (provider.blocking().isOn() && this.blockingHit(machine, this.patternInputs)) {
            return false;
        }
        try {
            // 收集这一单的全部资源与数量（数量比即样板比例）：只记录，不写机器。
            Map<AEKey, Long> needed = new LinkedHashMap<>();
            details.pushInputsToExternalInventory(inputHolder, (key, amount) -> {
                if (amount > 0L) {
                    needed.merge(key, amount, Long::sum);
                }
            });
            if (needed.isEmpty()) {
                return false;
            }
            // 整单放不下就一个都不写：整数份语义下，收不满的面等于给自己埋坑。
            CapacityPick capacity = this.node.capacityPick();
            if (capacity.portionsThatFit(machine, needed, this::configuredFace) < 1L) {
                return false;
            }
            List<PendingOrder.Line> lines = new ArrayList<>(needed.size());
            for (Map.Entry<AEKey, Long> entry : needed.entrySet()) {
                FacePick.PickResult pick = this.node.facePick().pick(machine, entry.getKey(),
                        IoSide.INPUT, this.configuredFace(entry.getKey()), null);
                if (!pick.picked()) {
                    return false;
                }
                lines.add(new PendingOrder.Line(entry.getKey(), entry.getValue(), pick.face()));
            }
            long now = this.node.gameTime();
            if (!provider.addOrder(new PendingOrder(lines, machine.id(), now, -1L))) {
                return false;
            }
        } catch (RuntimeException e) {
            // 还没往机器里写任何东西，报失败是安全的。
            this.node.logThrottled(this.node.gameTime(), "派单异常：" + e);
            return false;
        }
        this.markQueueChanged();
        this.node.digest().pending(provider.pendingCount(),
                provider.firstOrder() == null ? -1L : provider.firstOrder().enqueuedAt());
        this.node.returnSweep().activate(this.node.gameTime());
        return true;
    }

    /**
     * 每 tick 试投递队首：队列不退避，机器不在就等，机器被删就退回网络。
     *
     * <p>投递按「能完整做几份」算：所有行都能完整塞下的份数 n，n=0 一个都不写，
     * 避免把机器塞成半份、把后续任务卡死。P3 起订单可以跨 tick 分批，本阶段仍是一份。</p>
     *
     * @param now 当前游戏时间
     */
    void deliver(long now) {
        NodeProvider provider = this.node.node().provider();
        PendingOrder order = provider.firstOrder();
        NodeDigest digest = this.node.digest();
        if (order == null) {
            digest.pending(0, -1L);
            return;
        }
        digest.pending(provider.pendingCount(), order.enqueuedAt());
        Machine machine = order.machineId() == null ? null : this.node.machine(order.machineId());
        if (machine == null) {
            this.returnOrDiscard(provider, order, now);
            return;
        }
        if (!this.node.isAvailable()) {
            // 机器没加载：等，订单不丢。
            return;
        }
        PortExecutor ports = this.node.ports();
        if (!ports.loaded(machine)) {
            // 区块没加载：等。
            return;
        }
        List<MEStorage> targets = new ArrayList<>(order.lines().size());
        for (PendingOrder.Line line : order.lines()) {
            MEStorage storage = this.storageFor(machine, line, ports);
            if (storage == null) {
                return;
            }
            long accepted = storage.insert(line.key(), line.amount(), Actionable.SIMULATE,
                    AeKeySupport.SOURCE);
            if (accepted < line.amount()) {
                // 整数份：整单放不下就一个都不写，等机器腾出空间。
                return;
            }
            targets.add(storage);
        }
        List<PendingOrder.Line> remaining = new ArrayList<>();
        for (int index = 0; index < order.lines().size(); index++) {
            PendingOrder.Line line = order.lines().get(index);
            long want = line.amount();
            long inserted;
            try {
                inserted = targets.get(index).insert(line.key(), want, Actionable.MODULATE,
                        AeKeySupport.SOURCE);
            } catch (RuntimeException e) {
                // 这一行可能写进去一部分（未知）：按「整行已交付」处理，绝不重投造成复制；
                // 后面的行还没动，原样留回队首等下一轮。
                this.node.logThrottled(now, "投递异常：" + e);
                for (int rest = index + 1; rest < order.lines().size(); rest++) {
                    remaining.add(order.lines().get(rest));
                }
                break;
            }
            if (inserted < want) {
                // 模拟与提交不一致：差额按固定数量写回队首，下一轮精确补齐。
                remaining.add(new PendingOrder.Line(line.key(), want - inserted, line.face()));
            }
        }
        if (remaining.isEmpty()) {
            provider.removeFirstOrder();
        } else {
            provider.replaceFirstOrder(new PendingOrder(remaining, order.machineId(),
                    order.enqueuedAt(), order.orphanedAt()));
        }
        this.markQueueChanged();
        this.node.returnSweep().activate(now);
    }

    /**
     * 取一行要写的存储：订单绑定的面优先，收不下整行就重新选面。
     *
     * @param machine 机器
     * @param line    订单行
     * @param ports   端口缓存
     * @return 存储；拿不到时为 {@code null}
     */
    @Nullable
    private MEStorage storageFor(Machine machine, PendingOrder.Line line, PortExecutor ports) {
        Direction face = line.face();
        if (face != null) {
            MEStorage bound = ports.storage(machine, face);
            if (bound != null
                    && bound.insert(line.key(), line.amount(), Actionable.SIMULATE,
                            AeKeySupport.SOURCE) >= line.amount()) {
                return bound;
            }
        }
        FacePick.PickResult pick = this.node.facePick().pick(machine, line.key(), IoSide.INPUT,
                this.configuredFace(line.key()), null);
        return pick.picked() ? ports.storage(machine, pick.face()) : null;
    }

    /**
     * 阻挡判定：任何一个面上还有这台机器的样板输入就整单拒绝。
     *
     * <p>直接读机器各面的库存，按去组件 key 比较：读库存比逐个样板目标问「有没有这批输入」
     * 便宜，也正好是 P3 投递时要用的判据。</p>
     *
     * @param machine     机器
     * @param blockedKeys 这台机器所有样板输入的 key 并集（已去组件）
     * @return 是否撞到阻挡
     */
    private boolean blockingHit(Machine machine, Set<AEKey> blockedKeys) {
        if (blockedKeys.isEmpty()) {
            return false;
        }
        PortExecutor ports = this.node.ports();
        for (Direction face : FACES) {
            MEStorage storage = ports.storage(machine, face);
            if (storage == null) {
                continue;
            }
            KeyCounter counter = new KeyCounter();
            try {
                storage.getAvailableStacks(counter);
            } catch (RuntimeException e) {
                // 这一面读不出来：不影响其它面的判定，异常不外抛。
                continue;
            }
            for (AEKey key : counter.keySet()) {
                if (blockedKeys.contains(key.dropSecondary())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 节点输入规则里给这个 key 配的面：先精确、再同注册名的模糊。
     *
     * @param key 资源
     * @return 配置面；没配时为 {@code null}
     */
    @Nullable
    private Direction configuredFace(AEKey key) {
        Direction fuzzy = null;
        for (ResourceSpec spec : this.node.node().io().inputs()) {
            if (spec.face() == null) {
                continue;
            }
            if (spec.key().equals(key)) {
                return spec.face();
            }
            if (fuzzy == null && spec.fuzzy()
                    && spec.key().getType().equals(key.getType())
                    && spec.key().getId().equals(key.getId())) {
                fuzzy = spec.face();
            }
        }
        return fuzzy;
    }

    /** 队列或存档数据变了：让节点在下一 tick 统一落盘。 */
    private void markQueueChanged() {
        this.node.markDataChanged();
    }

    /**
     * 目标机器已被删：把订单退回 ME 网络；网络也不收就留成孤儿等重试，超时才丢弃。
     *
     * @param provider 供应器
     * @param order    订单
     * @param now      当前游戏时间
     */
    private void returnOrDiscard(NodeProvider provider, PendingOrder order, long now) {
        if (order.orphan() && now - order.orphanedAt() > PendingOrder.ORPHAN_TIMEOUT_TICKS) {
            // 网络长时间恢复不了：静默丢弃（只记日志，绝不生成掉落物实体）。
            provider.removeFirstOrder();
            this.markQueueChanged();
            this.node.logThrottled(now, "孤儿订单超时丢弃：" + order.lines().size() + " 行");
            this.node.digest().pending(provider.pendingCount(),
                    provider.firstOrder() == null ? -1L : provider.firstOrder().enqueuedAt());
            return;
        }
        var network = this.node.networkStorage();
        if (network == null) {
            // 只在第一次失败时记孤儿时刻，否则每 tick 刷新会让超时永远不触发。
            provider.replaceFirstOrder(order.orphan() ? order : order.asOrphan(now));
            this.markQueueChanged();
            return;
        }
        List<PendingOrder.Line> remaining = new ArrayList<>(order.lines().size());
        for (PendingOrder.Line line : order.lines()) {
            long accepted = network.insert(line.key(), line.amount(), Actionable.SIMULATE,
                    AeKeySupport.SOURCE);
            if (accepted <= 0L) {
                remaining.add(line);
                continue;
            }
            long inserted = network.insert(line.key(), Math.min(accepted, line.amount()),
                    Actionable.MODULATE, AeKeySupport.SOURCE);
            long left = line.amount() - inserted;
            if (left > 0L) {
                remaining.add(new PendingOrder.Line(line.key(), left, line.face()));
            }
        }
        if (remaining.isEmpty()) {
            provider.removeFirstOrder();
        } else {
            PendingOrder rest = order.withLines(remaining);
            // 同一条款：孤儿时刻只在第一次失败时记，不能每 tick 刷新。
            provider.replaceFirstOrder(order.orphan() ? rest : rest.asOrphan(now));
        }
        this.markQueueChanged();
        this.node.digest().pending(provider.pendingCount(),
                provider.firstOrder() == null ? -1L : provider.firstOrder().enqueuedAt());
    }

    /**
     * 节点销毁前把队列里的单全部退回网络；退不回的直接丢弃（只记日志，不生成掉落物）。
     *
     * @param now 当前游戏时间
     */
    void returnAllOrders(long now) {
        NodeProvider provider = this.node.node().provider();
        if (provider.pendingCount() == 0) {
            return;
        }
        var network = this.node.networkStorage();
        for (PendingOrder order : provider.pending()) {
            if (network != null) {
                for (PendingOrder.Line line : order.lines()) {
                    long accepted = network.insert(line.key(), line.amount(), Actionable.SIMULATE,
                            AeKeySupport.SOURCE);
                    if (accepted > 0L) {
                        network.insert(line.key(), Math.min(accepted, line.amount()),
                                Actionable.MODULATE, AeKeySupport.SOURCE);
                    }
                }
            }
        }
        this.node.logThrottled(now, "节点销毁，队列 " + provider.pendingCount() + " 单已尽力退回网络");
        provider.clearPending();
        this.markQueueChanged();
        this.node.digest().pending(0, -1L);
    }

    @Override
    public boolean isBusy() {
        return this.node.node().provider().pendingCount() > 0;
    }

    /** 机器上所有样板的输入 key 并集。 */
    Set<AEKey> patternInputs() {
        return this.patternInputs;
    }

    /** 机器上所有样板的产物 key 并集，回收白名单。 */
    Set<AEKey> products() {
        return this.products;
    }

    /** 成功解码的样板数。 */
    int decodedCount() {
        return this.patterns.size();
    }

    /**
     * 机器上下线时清掉智能倍率缓存：档位没变，能装几份变了。
     */
    void onOnlineChanged() {
        this.smartCache.clear();
    }

    /**
     * 重新解码全部样板并通知 CPU。
     *
     * <p>样板槽变化后调：AE2 的合成服务在节点注册时拍快照，不刷新的话 CPU 看到的永远是旧的。</p>
     */
    void rebuild() {
        NodeProvider provider = this.node.node().provider();
        Level level = this.node.decodeLevel();
        boolean modeChanged = this.smartCacheMode != provider.multiplier().mode();
        if (this.smartCacheRevision != this.node.node().topologyRevision() || modeChanged) {
            this.smartCache.clear();
            this.smartCacheRevision = this.node.node().topologyRevision();
            this.smartCacheMode = provider.multiplier().mode();
        }
        List<IPatternDetails> decoded = new ArrayList<>();
        Set<AEKey> inputs = new HashSet<>();
        Set<AEKey> outputs = new HashSet<>();
        Set<ItemStack> liveKeys = new HashSet<>();
        for (int slot = 0; slot < provider.capacity(); slot++) {
            ItemStack stack = provider.pattern(slot);
            // 槽位只收处理样板；老档被手改过时也在这里挡掉，不能拿合成样板去广告。
            if (stack.isEmpty() || !AEItems.PROCESSING_PATTERN.is(stack)) {
                continue;
            }
            IPatternDetails details = decode(stack, level);
            if (details == null) {
                continue;
            }
            liveKeys.add(stack.copyWithCount(1));
            int factor = resolveFactor(provider, stack, details);
            ScaledPatternDetails scaled = ScaledPatternDetails.scale(details, factor);
            decoded.add(scaled);
            for (IPatternDetails.IInput input : details.getInputs()) {
                for (GenericStack possible : input.getPossibleInputs()) {
                    inputs.add(possible.what().dropSecondary());
                }
            }
            for (GenericStack output : details.getOutputs()) {
                outputs.add(output.what());
            }
        }
        // 样板被删掉的条目立即清掉，避免缓存跟着旧样板越攒越多。
        this.smartCache.keySet().retainAll(liveKeys);
        this.patterns = List.copyOf(decoded);
        this.patternInputs = Set.copyOf(inputs);
        this.products = Set.copyOf(outputs);
        this.publish();
    }

    /**
     * 解析一张样板这次使用的倍数。
     *
     * @param provider 节点 provider
     * @param stack    样板物品，智能缓存键
     * @param details  原始样板
     * @return 倍数；智能探测失败回退 1
     */
    private int resolveFactor(NodeProvider provider, ItemStack stack, IPatternDetails details) {
        return switch (provider.multiplier().mode()) {
            case OFF -> 1;
            case FIXED -> provider.multiplier().resolve(0);
            case SMART -> this.smartCapacity(stack, details);
        };
    }

    /**
     * 智能倍率：按节点所有在线机器的容量之和取 25%（向下取整，至少 1）。
     *
     * <p>每张样板各算各的：不同样板的每份用量不同，能装的份数自然不同。结果缓存到样板增删、
     * 机器上下线或倍率模式切换为止；网络压力大时不会每 tick 重扫机器。</p>
     *
     * @param stack   样板物品
     * @param details 原始样板
     * @return 可装份数，至少 1
     */
    private int smartCapacity(ItemStack stack, IPatternDetails details) {
        ItemStack key = stack.copyWithCount(1);
        Integer cached = this.smartCache.get(key);
        if (cached != null) {
            return Math.max(1, cached);
        }
        int capacity = probeCapacity(details);
        this.smartCache.put(key, capacity);
        return capacity;
    }

    /**
     * 节点全部在线机器的可装份数之和的 25%。
     *
     * @param details 原始样板
     * @return 份数，至少 1
     */
    private int probeCapacity(IPatternDetails details) {
        List<Machine> online = this.node.onlineMachines();
        if (online.isEmpty()) {
            return 1;
        }
        Map<AEKey, Long> perPortion = perPortion(details);
        if (perPortion.isEmpty()) {
            return 1;
        }
        CapacityPick capacity = this.node.capacityPick();
        long total = 0L;
        for (Machine machine : online) {
            total += capacity.portionsThatFit(machine, perPortion);
            if (total > Integer.MAX_VALUE) {
                total = Integer.MAX_VALUE;
                break;
            }
        }
        return Math.max(1, (int) (total / 4L));
    }

    /**
     * 一张样板「一份」的输入清单：资源 → 用量。
     *
     * <p>AE2 的实际消耗 = 模板数量 × {@code getMultiplier()}，所以这里两个都要乘进去；只取第一个
     * 候选输入（与投递一致），少了 multiplier 会把可装份数估大。</p>
     *
     * @param details 原始样板
     * @return 每份输入；乘不出合法数量时返回空表
     */
    private static Map<AEKey, Long> perPortion(IPatternDetails details) {
        Map<AEKey, Long> result = new LinkedHashMap<>();
        for (IPatternDetails.IInput input : details.getInputs()) {
            GenericStack[] possible = input.getPossibleInputs();
            if (possible.length == 0) {
                continue;
            }
            try {
                long amount = Math.multiplyExact(possible[0].amount(), input.getMultiplier());
                if (amount > 0L) {
                    result.merge(possible[0].what(), amount, Long::sum);
                }
            } catch (ArithmeticException e) {
                return Map.of();
            }
        }
        return result;
    }

    /**
     * 机器上下线时只重发广告，不重复解码。
     */
    void publish() {
        this.node.requestPatternUpdate();
    }

    /**
     * 解一张样板；坏样板按「没有这张样板」处理，不能让它把整台机器带崩。
     *
     * @param stack 处理样板物品
     * @param level 解码用的世界；处理样板不需要它，但 AE2 的接口要求
     * @return 样板详情；解不出来时为 {@code null}
     */
    private static IPatternDetails decode(ItemStack stack, Level level) {
        try {
            return PatternDetailsHelper.decodePattern(stack, level);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
