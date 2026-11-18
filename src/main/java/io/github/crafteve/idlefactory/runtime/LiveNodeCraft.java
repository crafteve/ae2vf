package io.github.crafteve.idlefactory.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.io.CapacityPick;
import io.github.crafteve.idlefactory.node.ProviderMode;
import io.github.crafteve.idlefactory.node.MultiplierMode;
import io.github.crafteve.idlefactory.node.NodeCraft;
import io.github.crafteve.idlefactory.node.NodeDispatchOrder;
import io.github.crafteve.idlefactory.node.NodeDispatchQueue;
import io.github.crafteve.idlefactory.machine.Machine;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.core.definitions.AEItems;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 机器的虚拟样板供应器：把机器上的样板广告给 CPU，并接住 CPU 派来的材料。
 *
 * <p>这是挂到虚拟节点上的 {@link ICraftingProvider} 服务，生命周期跟着节点走。AE2 的节点服务
 * 只能在 {@code create} 之前挂，所以它常驻，样板为空时就是「没有任何样板的供应器」。</p>
 *
 * <p>P3 起它只留 AE2 协议：广告、{@code pushPattern}（校验 + 拆份 + 入队）与 {@code isBusy}。
 * 选面、分配机器、退避与回收全在执行层：{@code pushPattern} 不碰机器，也<b>不做阻挡判定</b>
 * ——「所有机器都挡」要先扫一遍所有机器库存，一点都不便宜，那个判断跟着投递走。</p>
 *
 * <p>样板解码结果、输入的 key 集合与产物 key 集合都缓存在这里，只在样板变化或机器上下线时
 * 重建；CPU 的合成规划直接读缓存，不会每 tick 解码样板。</p>
 */
final class LiveNodeCraft implements ICraftingProvider {
    private final LiveNode node;
    /** 当前能用的样板；机器方块不在时对外仍报空。 */
    private List<IPatternDetails> patterns = List.of();
    /** 所有样板输入的并集，已去掉组件（阻挡判定按这个比较）。 */
    private Set<AEKey> patternInputs = Set.of();
    /** 所有样板产物的并集，回收白名单只有这些。 */
    private Set<AEKey> products = Set.of();
    /**
     * 智能倍率的容量缓存：样板 → 探测当时的可装份数。
     *
     * <p>键是样板本身，值是探测那一刻「节点在线机器还能装几份」的合计。只在样板增删、机器上下线、
     * 倍率模式切换时重算，不随机器内容反复探测：机器空着时这个值就是容量，机器里有料时它会偏小，
     * 偏小只会让单批下得少一些，不会超发。收不下的部分由投递按整数份处理。</p>
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
    LiveNodeCraft(LiveNode node) {
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
        NodeCraft provider = this.node.node().provider();
        if (this.isBusy() || !this.node.isAvailable() || !this.node.isNodeOnline()) {
            return false;
        }
        if (!this.patterns.contains(details) || !details.supportsPushInputsToExternalInventory()) {
            return false;
        }
        try {
            // 收集这一单的全部资源与数量：数量比即样板比例。
            Map<AEKey, Long> needed = new LinkedHashMap<>();
            details.pushInputsToExternalInventory(inputHolder, (key, amount) -> {
                if (amount > 0L) {
                    needed.merge(key, amount, Long::sum);
                }
            });
            if (needed.isEmpty()) {
                return false;
            }
            // 拆份：倍率样板一次派下来的量是「原量 × 倍数」，按倍数拆回每份用量，
            // 这样订单可以分给多台机器、跨 tick 交。交叉校验失败就整单按一份处理，绝不猜。
            long portions = this.factorOf(details);
            Map<AEKey, Long> perPortion = new LinkedHashMap<>(needed.size());
            for (Map.Entry<AEKey, Long> entry : needed.entrySet()) {
                if (portions <= 1L || entry.getValue() % portions != 0L) {
                    portions = 1L;
                    break;
                }
                perPortion.put(entry.getKey(), entry.getValue() / portions);
            }
            if (portions <= 1L) {
                perPortion.clear();
                perPortion.putAll(needed);
                portions = 1L;
            }
            List<NodeDispatchOrder.Line> lines = new ArrayList<>(perPortion.size());
            for (Map.Entry<AEKey, Long> entry : perPortion.entrySet()) {
                if (entry.getValue() <= 0L) {
                    return false;
                }
                lines.add(new NodeDispatchOrder.Line(entry.getKey(), entry.getValue()));
            }
            long now = this.node.gameTime();
            NodeDispatchQueue queue = provider.pending();
            if (!queue.enqueue(NodeDispatchOrder.create(UUID.randomUUID(), lines, portions, now))) {
                return false;
            }
        } catch (RuntimeException e) {
            // 还没往机器里写任何东西，报失败是安全的。
            this.node.logThrottled(this.node.gameTime(), "派单异常：" + e);
            return false;
        }
        this.node.markDataChanged();
        return true;
    }

    /**
     * 这次派单对应的倍数：倍率样板才能拆份，普通样板恒为 1。
     *
     * @param details CPU 派下来的样板（可能已被倍率放大）
     * @return 份数，至少 1
     */
    private long factorOf(IPatternDetails details) {
        return details instanceof LiveNodePatternDetails scaled ? Math.max(1, scaled.factor()) : 1L;
    }

    @Override
    public boolean isBusy() {
        return this.node.node().provider().pending().size() > 0;
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
        NodeCraft provider = this.node.node().provider();
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
            decoded.add(LiveNodePatternDetails.scale(details, factor));
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
    private int resolveFactor(NodeCraft provider, ItemStack stack, IPatternDetails details) {
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
     * 节点全部在线机器「还能装几份」之和的 25%。
     *
     * <p>用探测那一刻的空位代表容量：机器空着时两者相等；机器里有料时结果偏小（保守），
     * 不会让 CPU 下一批超过机器装得下的量。结果只在 {@link #smartCache} 失效时重算。</p>
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
