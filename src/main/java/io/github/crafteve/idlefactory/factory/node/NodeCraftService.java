package io.github.crafteve.idlefactory.factory.node;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.node.NodeProvider;
import io.github.crafteve.idlefactory.factory.node.ProviderMode;

import appeng.api.config.Actionable;
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
 * 机器的虚拟样板供应器：把机器上的样板广告给 CPU，并接住 CPU 派来的一份输入。
 *
 * <p>这是挂到虚拟节点上的 {@link ICraftingProvider} 服务，生命周期跟着节点走。AE2 的节点服务
 * 只能在 {@code create} 之前挂，所以它常驻，样板为空时就是「没有任何样板的供应器」。</p>
 *
 * <p>样板解码结果、输入的 key 集合（阻挡判定用）与产物 key 集合（回收白名单用）都缓存在这里，
 * 只在样板变化或机器上下线时重建；CPU 的合成规划直接读缓存，不会每 tick 解码样板。</p>
 */
final class NodeCraftService implements ICraftingProvider {
    private final NodeRuntime node;
    /** 当前能用的样板；机器方块不在时对外仍报空。 */
    private List<IPatternDetails> patterns = List.of();
    /** 所有样板输入的并集，已去掉组件（AE2 的阻挡判定按这个比较）。 */
    private Set<AEKey> patternInputs = Set.of();
    /** 所有样板产物的并集，回收白名单只有这些。 */
    private Set<AEKey> products = Set.of();
    /**
     * 智能倍率的静态容量缓存：样板 → 探测当时的可装份数。
     *
     * <p>键是样板本身，值是「一台机器能装几份」的上限，不是实时空位；机器逐渐变满后这个值
     * 可能偏大，收不下的部分走余量，不做每次 push 重探测。</p>
     */
    private final java.util.Map<ItemStack, Integer> smartCache = new java.util.HashMap<>();
    /** 缓存对应的节点拓扑版本；结构变化后整体作废。 */
    private long smartCacheRevision = -1L;

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
        // 一次派单只挑一台在线机器，整份货给它；不拆分到别的机器。
        Machine machine = this.node.pickMachine();
        if (machine == null) {
            return false;
        }
        ProviderFaceResolver.Faces faces = this.node.faces(machine);
        if (faces == null) {
            return false;
        }
        // 已经往机器里塞过东西之后不能再报失败：CPU 会把同一份输入再推给下一家，造成复制。
        boolean[] committed = {false};
        try {
            if (provider.blocking().isOn() && faces.blockingHit(this.patternInputs)) {
                return false;
            }
            Map<AEKey, Long> needed = new LinkedHashMap<>();
            for (KeyCounter counter : inputHolder) {
                for (var entry : counter) {
                    if (entry.getLongValue() > 0L) {
                        needed.merge(entry.getKey(), entry.getLongValue(), Long::sum);
                    }
                }
            }
            if (needed.isEmpty()) {
                return false;
            }
            Map<AEKey, ProviderFaceResolver.Faces.Target> selections =
                    new LinkedHashMap<>(needed.size());
            for (Map.Entry<AEKey, Long> entry : needed.entrySet()) {
                // 只要能收 1 个就选它：固定倍率大于容量时，多出的部分进余量而不是整单失败。
                ProviderFaceResolver.Faces.Target target =
                        faces.select(entry.getKey(), 1L);
                if (target == null) {
                    return false;
                }
                selections.put(entry.getKey(), target);
            }
            details.pushInputsToExternalInventory(inputHolder, (key, amount) -> {
                if (amount <= 0L) {
                    return;
                }
                ProviderFaceResolver.Faces.Target target = selections.get(key);
                long inserted = target == null
                        ? 0L : faces.insert(target, key, amount, Actionable.MODULATE);
                if (inserted > 0L) {
                    committed[0] = true;
                }
                if (inserted < amount) {
                    // 收不下的进余量，绑定原机器；不回退成失败，避免 CPU 复制。
                    ProviderOverflow.push(this.node, machine.id(), key, amount - inserted,
                            target == null ? null : target.face());
                }
            });
            committed[0] = true;
            this.node.returnSweep().activate(this.node.gameTime());
            return true;
        } catch (RuntimeException e) {
            this.node.logThrottled(this.node.gameTime(), "派单异常：" + e);
            return committed[0];
        }
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
     * 重新解码全部样板并通知 CPU。
     *
     * <p>样板槽变化后调：AE2 的合成服务在节点注册时拍快照，不刷新的话 CPU 看到的永远是旧的。</p>
     */
    void rebuild() {
        NodeProvider provider = this.node.node().provider();
        Level level = this.node.decodeLevel();
        if (this.smartCacheRevision != this.node.node().topologyRevision()) {
            this.smartCache.clear();
            this.smartCacheRevision = this.node.node().topologyRevision();
        }
        List<IPatternDetails> decoded = new ArrayList<>();
        Set<AEKey> inputs = new HashSet<>();
        Set<AEKey> outputs = new HashSet<>();
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
            int factor = resolveFactor(provider, stack, details);
            ScaledPatternDetails scaled = ScaledPatternDetails.scale(details, factor);
            if (scaled == null) {
                // 乘法溢出：跳过并记日志，不产生负数数量。
                this.node.logThrottled(this.node.gameTime(),
                        "样板倍率溢出，已跳过：" + details.getDefinition().getId());
                continue;
            }
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
     * 智能倍率：按「一台机器能装几份」二分探测，缓存静态上限。
     *
     * <p>只用 {@code Actionable.SIMULATE}，绝不写入机器状态；多个输入要求同一倍数都能装下，
     * 等价于取最小值。探测失败（没有在线机器 / 没有可收面）回退 ×1。</p>
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
     * 二分探测一台机器能装几份。
     *
     * @param details 原始样板
     * @return 可装份数；探测不可用时为 1
     */
    private int probeCapacity(IPatternDetails details) {
        List<Machine> online = this.node.onlineMachines();
        if (online.isEmpty()) {
            return 1;
        }
        ProviderFaceResolver.Faces faces = this.node.faces(online.get(0));
        if (faces == null) {
            return 1;
        }
        int low = 1;
        int high = Multiplier.HARD_MAX_FACTOR;
        int best = 0;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (canFit(details, faces, mid)) {
                best = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return Math.max(1, best);
    }

    /**
     * 这份样板按倍数能不能全部塞进这台机器（模拟插入）。
     *
     * @param details 原始样板
     * @param faces   目标机器的面快照
     * @param factor  倍数
     * @return 所有输入都能收下时为 {@code true}
     */
    private static boolean canFit(IPatternDetails details, ProviderFaceResolver.Faces faces,
            int factor) {
        for (IPatternDetails.IInput input : details.getInputs()) {
            GenericStack[] possible = input.getPossibleInputs();
            if (possible.length == 0) {
                continue;
            }
            long amount;
            try {
                amount = Math.multiplyExact(possible[0].amount(), (long) factor);
            } catch (ArithmeticException e) {
                return false;
            }
            if (faces.select(possible[0].what(), amount) == null) {
                return false;
            }
        }
        return true;
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
