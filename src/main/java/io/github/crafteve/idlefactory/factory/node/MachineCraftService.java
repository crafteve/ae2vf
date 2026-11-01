package io.github.crafteve.idlefactory.factory.node;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.crafteve.idlefactory.factory.machine.MachineMode;
import io.github.crafteve.idlefactory.factory.machine.MachineProvider;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.core.definitions.AEItems;
import appeng.helpers.patternprovider.PatternProviderTarget;

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
 */
final class MachineCraftService implements ICraftingProvider {
    private final MachineNode node;
    /** 当前能用的样板；机器方块不在时对外仍报空。 */
    private List<IPatternDetails> patterns = List.of();
    /** 所有样板输入的并集，已去掉组件（AE2 的阻挡判定按这个比较）。 */
    private Set<AEKey> patternInputs = Set.of();
    /** 所有样板产物的并集，回收白名单只有这些。 */
    private Set<AEKey> products = Set.of();

    /**
     * @param node 所属机器节点
     */
    MachineCraftService(MachineNode node) {
        this.node = node;
    }

    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        // 被动模式只是「样板存着但不参与合成」：节点保留，样板访问终端仍能上传，只是 CPU 看不到。
        return this.node.isAvailable() && this.node.machine().provider().mode() == MachineMode.ACTIVE
                ? this.patterns : List.of();
    }

    @Override
    public boolean pushPattern(IPatternDetails details, KeyCounter[] inputHolder) {
        MachineProvider provider = this.node.machine().provider();
        if (this.isBusy() || !this.node.isAvailable() || !this.node.isNodeOnline()) {
            return false;
        }
        if (!this.patterns.contains(details) || !details.supportsPushInputsToExternalInventory()) {
            return false;
        }
        ProviderFaceResolver.Faces faces = this.node.faces();
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
            Map<AEKey, Direction> selections = new LinkedHashMap<>(needed.size());
            for (Map.Entry<AEKey, Long> entry : needed.entrySet()) {
                Direction face = faces.select(entry.getKey(), entry.getValue());
                if (face == null) {
                    return false;
                }
                selections.put(entry.getKey(), face);
            }
            details.pushInputsToExternalInventory(inputHolder, (key, amount) -> {
                if (amount <= 0L) {
                    return;
                }
                Direction face = selections.get(key);
                PatternProviderTarget target = face == null ? null : faces.target(face);
                long inserted = target == null ? 0L : target.insert(key, amount, Actionable.MODULATE);
                if (inserted > 0L) {
                    committed[0] = true;
                }
                if (inserted < amount) {
                    ProviderOverflow.push(this.node, key, amount - inserted, face);
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
        return this.node.machine().provider().pendingCount() > 0;
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
        MachineProvider provider = this.node.machine().provider();
        Level level = this.node.decodeLevel();
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
            decoded.add(details);
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
