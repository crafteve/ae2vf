package io.github.crafteve.idlefactory.client.screen;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder;
import io.github.crafteve.idlefactory.factory.node.NodeIoGroup;
import io.github.crafteve.idlefactory.factory.machine.ResourceSpec;

import net.minecraft.resources.ResourceLocation;

/**
 * 界面上的一个机器行：一台或多台「同方块、同方块实体、同 IO 规则」的机器。
 *
 * <p><b>组是视图，不是存档实体。</b>规则跟着机器走；删掉这个类不会丢任何数据。分组每次从最新
 * 快照现算，不缓存跨快照的状态。</p>
 *
 * <p>P1 删除产线后左表不再有「用户拖出来的堆叠行」：左表一行就是一台机器
 * （{@link #flat}），只有右侧机器库还按「同方块、同规则」自动合并（{@link #library}）。</p>
 *
 * <p>组身份用锚点（组内第一台）的 machineId 表示，而不是签名：签名在玩家改规则时会变，
 * 但组还在。界面状态（展开、悬停）绑锚点 id 才稳定。</p>
 *
 * @param members     成员视图，按输入顺序；第一个是锚点
 * @param blockId     分组用的方块 id；未加载时是最后一次看到的值
 * @param beTypeId    方块实体类型 id，用来区分同一个方块的多个等级
 * @param signature   规则签名，用来判断还能不能并进新成员
 * @param statusKind  聚合后的状态种类
 * @param statusCount 和 {@code statusKind} 一致的成员数；小于成员数时界面显示 {@code x/y}
 * @param inputs      锚点的输入规则
 * @param outputs     锚点的输出规则
 * @param inputRates  与 {@code inputs} 同序的整组累加速率
 * @param outputRates 与 {@code outputs} 同序的整组累加速率
 */
record FactoryMachineStack(List<FactorySnapshotBuilder.MachineView> members,
        @Nullable ResourceLocation blockId,
        @Nullable ResourceLocation beTypeId, NodeIoGroup.Signature signature,
        FactoryStatus.Kind statusKind, int statusCount,
        List<ResourceSpec> inputs, List<ResourceSpec> outputs,
        List<Long> inputRates, List<Long> outputRates) {

    FactoryMachineStack {
        members = List.copyOf(members);
        if (members.isEmpty()) {
            throw new IllegalArgumentException("机器组不能为空");
        }
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        inputRates = List.copyOf(inputRates);
        outputRates = List.copyOf(outputRates);
    }

    /** 分组键：同键才能并成一行。 */
    private record StackKey(@Nullable ResourceLocation blockId, @Nullable ResourceLocation beTypeId,
            NodeIoGroup.Signature signature) {
    }

    /**
     * 把机器库铺成一行一台。
     *
     * <p>左表不做自动合并：每台机器都有自己的 IO 规则和坐标，合并只会让「改哪台」变模糊。
     * 顺序沿用快照顺序（机器库登记顺序）。</p>
     *
     * @param machines 机器视图
     * @return 组列表；每项恰好一台
     */
    static List<FactoryMachineStack> flat(List<FactorySnapshotBuilder.MachineView> machines) {
        List<FactoryMachineStack> stacks = new ArrayList<>(machines.size());
        for (FactorySnapshotBuilder.MachineView machine : machines) {
            stacks.add(of(List.of(machine)));
        }
        return stacks;
    }

    /**
     * 把整个机器库按组归并。
     *
     * <p>机器库是只读展示，按「同方块、同规则」自动合并；不再按产线分组。</p>
     *
     * @param machines 机器库视图
     * @return 组列表
     */
    static List<FactoryMachineStack> library(List<FactorySnapshotBuilder.MachineView> machines) {
        return group(machines, view -> new StackKey(view.groupBlockId(), view.beTypeId(),
                signatureOf(view)));
    }

    /**
     * 从一批成员构造一组。
     *
     * @param members 成员视图，第一个是锚点
     * @return 组
     */
    static FactoryMachineStack of(List<FactorySnapshotBuilder.MachineView> members) {
        FactorySnapshotBuilder.MachineView anchor = members.get(0);
        Map<FactoryStatus.Kind, Integer> counts = new EnumMap<>(FactoryStatus.Kind.class);
        for (FactorySnapshotBuilder.MachineView member : members) {
            counts.merge(FactoryStatus.kindOf(member), 1, Integer::sum);
        }
        FactoryStatus.Kind dominant = dominant(counts);
        return new FactoryMachineStack(members, anchor.groupBlockId(),
                anchor.beTypeId(), signatureOf(anchor), dominant,
                counts.getOrDefault(dominant, 0), anchor.inputs(), anchor.outputs(),
                stackRates(members, true, anchor.inputs()),
                stackRates(members, false, anchor.outputs()));
    }

    /**
     * 从规则列表算签名。
     *
     * <p>包内可见：状态层算「这一组最多还能扩到几台」时要拿别的机器视图比签名，
     * 不能再写一份构造逻辑。</p>
     *
     * @param view 机器视图
     * @return 签名
     */
    static NodeIoGroup.Signature signatureOf(FactorySnapshotBuilder.MachineView view) {
        return new NodeIoGroup.Signature(Set.copyOf(view.inputs()), Set.copyOf(view.outputs()));
    }

    private static List<FactoryMachineStack> group(List<FactorySnapshotBuilder.MachineView> views,
            Function<FactorySnapshotBuilder.MachineView, StackKey> keyOf) {
        Map<StackKey, List<FactorySnapshotBuilder.MachineView>> buckets = new LinkedHashMap<>();
        for (FactorySnapshotBuilder.MachineView view : views) {
            buckets.computeIfAbsent(keyOf.apply(view), key -> new ArrayList<>()).add(view);
        }
        List<FactoryMachineStack> stacks = new ArrayList<>(buckets.size());
        for (List<FactorySnapshotBuilder.MachineView> members : buckets.values()) {
            stacks.add(of(members));
        }
        return stacks;
    }

    /**
     * 聚合状态：先看红色和黄色，再取多数。
     *
     * <p>规则来自产品要求：「有报错显示报错，没有就显示平均水平」。缺料不算报错——它是黄灯
     * 常态，所以不参与优先级，只在「没有失联/堵塞/未载」时按多数出现。</p>
     *
     * @param counts 各状态出现次数
     * @return 聚合状态；次数相同取枚举靠前的，保证结果稳定
     */
    private static FactoryStatus.Kind dominant(Map<FactoryStatus.Kind, Integer> counts) {
        for (FactoryStatus.Kind urgent : List.of(FactoryStatus.Kind.OFFLINE,
                FactoryStatus.Kind.INPUT_BLOCKED, FactoryStatus.Kind.UNPROBED,
                FactoryStatus.Kind.STALE)) {
            if (counts.containsKey(urgent)) {
                return urgent;
            }
        }
        FactoryStatus.Kind best = FactoryStatus.Kind.UNKNOWN;
        int bestCount = -1;
        for (FactoryStatus.Kind kind : FactoryStatus.Kind.values()) {
            int count = counts.getOrDefault(kind, 0);
            if (count > bestCount) {
                best = kind;
                bestCount = count;
            }
        }
        return best;
    }

    private static List<Long> stackRates(List<FactorySnapshotBuilder.MachineView> members,
            boolean input, List<ResourceSpec> anchorRules) {
        List<Long> rates = new ArrayList<>(anchorRules.size());
        for (ResourceSpec spec : anchorRules) {
            long total = 0L;
            for (FactorySnapshotBuilder.MachineView member : members) {
                List<ResourceSpec> rules = input ? member.inputs() : member.outputs();
                List<Long> memberRates = input ? member.inputRates() : member.outputRates();
                for (int i = 0; i < rules.size(); i++) {
                    if (rules.get(i).key().equals(spec.key())) {
                        total += memberRates.get(i);
                        break;
                    }
                }
            }
            rates.add(total);
        }
        return rates;
    }

    /** 锚点机器：取图标、名字都用它。 */
    FactorySnapshotBuilder.MachineView anchor() {
        return this.members.get(0);
    }

    /** 组身份：锚点机器 id。 */
    UUID anchorId() {
        return anchor().machineId();
    }

    /** 台数。 */
    int count() {
        return this.members.size();
    }

    /** 是不是多台；只有多台才画数量徽标。 */
    boolean stacked() {
        return this.members.size() > 1;
    }

    /** 输入侧整组速率。 */
    long rateIn() {
        long total = 0L;
        for (long rate : this.inputRates) {
            total += rate;
        }
        return total;
    }

    /** 输出侧整组速率。 */
    long rateOut() {
        long total = 0L;
        for (long rate : this.outputRates) {
            total += rate;
        }
        return total;
    }

    /** 两侧整组速率。 */
    long totalRate() {
        return rateIn() + rateOut();
    }

    /**
     * 聚合状态展示。
     *
     * @param theme 当前主题
     * @return 状态文字、颜色与贴图
     */
    FactoryStatus status(FactoryGuiTheme theme) {
        return FactoryStatus.of(this.statusKind, theme);
    }

    /**
     * 聚合状态文字；有分票时带 {@code x/y}。
     *
     * @param theme 当前主题
     * @return 显示文字
     */
    String statusText(FactoryGuiTheme theme) {
        String text = status(theme).text();
        if (this.statusCount < count()) {
            return text + " " + this.statusCount + "/" + count();
        }
        return text;
    }
}
