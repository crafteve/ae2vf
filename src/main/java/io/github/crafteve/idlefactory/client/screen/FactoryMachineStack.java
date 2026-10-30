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
import io.github.crafteve.idlefactory.factory.machine.MachineIoGroup;
import io.github.crafteve.idlefactory.factory.resource.ResourceSpec;

import net.minecraft.resources.ResourceLocation;

/**
 * 界面上的一个机器组：同产线、同方块、同方块实体、同 IO 规则的若干台机器。
 *
 * <p><b>组是视图，不是存档实体。</b>产线仍然存 N 个 machineId，规则仍然跟着机器走；删掉这个类
 * 不会丢任何数据。分组每次从最新快照现算，不缓存跨快照的状态。</p>
 *
 * <p>不变量：成员必定同产线、同 {@code blockId}、同 {@code beTypeId}、同规则签名。任何一条
 * 不满足就说明它已经分叉，必须单独成组——这里不做「修复」，只按事实呈现。</p>
 *
 * <p>组身份用锚点（组内第一台）的 machineId 表示，而不是签名：签名在玩家改规则时会变，
 * 但组还在。界面状态（展开、悬停、滚轮待发数量）绑锚点 id 才稳定。</p>
 *
 * <p>速率是整组累加：同组规则相同，但每台的实际速率不同，所以每条规则的速率是各成员之和。
 * 规则顺序取锚点自己的顺序，只是显示顺序，不参与分组判断。</p>
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
        @Nullable ResourceLocation blockId, @Nullable ResourceLocation beTypeId,
        MachineIoGroup.Signature signature, FactoryStatus.Kind statusKind, int statusCount,
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
            MachineIoGroup.Signature signature, List<UUID> lineIds) {
        StackKey {
            lineIds = List.copyOf(lineIds);
        }
    }

    /**
     * 把一条产线里的机器按组归并。
     *
     * <p>左表已经限定了一条产线，所以分组键不带产线；产线内同方块、同规则的机器就并成一行。</p>
     *
     * @param lineMachines 当前产线的机器，按界面顺序
     * @return 组列表；顺序是组内第一个成员出现的先后
     */
    static List<FactoryMachineStack> table(List<FactorySnapshotBuilder.MachineView> lineMachines) {
        return group(lineMachines, view -> new StackKey(view.groupBlockId(), view.beTypeId(),
                signatureOf(view), List.of()));
    }

    /**
     * 把整个机器库按组归并。
     *
     * <p>机器库会同时显示已编线和未编线的机器，所以分组键必须带产线 id，否则同方块同规则的
     * 两条产线会被并成一行。</p>
     *
     * @param machines 机器库视图
     * @return 组列表
     */
    static List<FactoryMachineStack> library(List<FactorySnapshotBuilder.MachineView> machines) {
        return group(machines, view -> new StackKey(view.groupBlockId(), view.beTypeId(),
                signatureOf(view), view.lineIds()));
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
    static MachineIoGroup.Signature signatureOf(FactorySnapshotBuilder.MachineView view) {
        return new MachineIoGroup.Signature(Set.copyOf(view.inputs()), Set.copyOf(view.outputs()));
    }

    private static List<FactoryMachineStack> group(List<FactorySnapshotBuilder.MachineView> views,
            Function<FactorySnapshotBuilder.MachineView, StackKey> keyOf) {
        Map<StackKey, List<FactorySnapshotBuilder.MachineView>> buckets = new LinkedHashMap<>();
        for (FactorySnapshotBuilder.MachineView view : views) {
            buckets.computeIfAbsent(keyOf.apply(view), key -> new ArrayList<>()).add(view);
        }
        List<FactoryMachineStack> stacks = new ArrayList<>(buckets.size());
        for (Map.Entry<StackKey, List<FactorySnapshotBuilder.MachineView>> entry
                : buckets.entrySet()) {
            stacks.add(create(entry.getKey(), entry.getValue()));
        }
        return stacks;
    }

    private static FactoryMachineStack create(StackKey key,
            List<FactorySnapshotBuilder.MachineView> members) {
        FactorySnapshotBuilder.MachineView anchor = members.get(0);
        Map<FactoryStatus.Kind, Integer> counts = new EnumMap<>(FactoryStatus.Kind.class);
        for (FactorySnapshotBuilder.MachineView member : members) {
            counts.merge(FactoryStatus.kindOf(member), 1, Integer::sum);
        }
        FactoryStatus.Kind dominant = dominant(counts);
        return new FactoryMachineStack(members, key.blockId(), key.beTypeId(), key.signature(),
                dominant, counts.getOrDefault(dominant, 0),
                anchor.inputs(), anchor.outputs(),
                stackRates(members, true, anchor.inputs()),
                stackRates(members, false, anchor.outputs()));
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
                FactoryStatus.Kind.INPUT_BLOCKED, FactoryStatus.Kind.UNLOADED)) {
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

    /** 锚点机器：取图标、名字、产线名都用它。 */
    FactorySnapshotBuilder.MachineView anchor() {
        return this.members.get(0);
    }

    /** 组身份：锚点机器 id。 */
    UUID anchorId() {
        return anchor().ref().id();
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
        return FactoryStatus.of(this.statusKind, anchor(), theme);
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
