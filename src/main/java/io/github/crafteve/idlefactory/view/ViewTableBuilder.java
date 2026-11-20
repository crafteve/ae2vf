package io.github.crafteve.idlefactory.view;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.config.IdleFactoryConfig;
import io.github.crafteve.idlefactory.io.IoAnalyzer;
import io.github.crafteve.idlefactory.io.IoForm;
import io.github.crafteve.idlefactory.io.IoKey;
import io.github.crafteve.idlefactory.io.IoReason;
import io.github.crafteve.idlefactory.io.IoSide;
import io.github.crafteve.idlefactory.io.PassivePlanner;
import io.github.crafteve.idlefactory.machine.Machine;
import io.github.crafteve.idlefactory.machine.MachineHealth;
import io.github.crafteve.idlefactory.machine.ResourceSpec;
import io.github.crafteve.idlefactory.node.Node;
import io.github.crafteve.idlefactory.node.NodeDispatchOrder;
import io.github.crafteve.idlefactory.node.NodeDispatchQueue;
import io.github.crafteve.idlefactory.node.NodeHealthResolver;
import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.observation.BlockFacts;
import io.github.crafteve.idlefactory.observation.NodeHealth;
import io.github.crafteve.idlefactory.observation.ObservationReader;
import io.github.crafteve.idlefactory.observation.WorkState;
import io.github.crafteve.idlefactory.resource.AeKeyAdapter;
import io.github.crafteve.idlefactory.runtime.LiveNode;
import io.github.crafteve.idlefactory.runtime.LiveNodeApply;
import io.github.crafteve.idlefactory.view.ViewNode.Activity;

/**
 * 视图表的唯一组装点：工厂配置（L0）+ 观测（L1）+ 执行结果（L3）→ {@link ViewTable}。
 *
 * <p>无状态、不写档、不探世界：所有事实分别来自 {@link ObservationReader} 的缓存、
 * {@link IoForm} 的条目、{@link IoAnalyzer} 的速率统计与 {@link LiveNodeApply} 的待投递队列，
 * 三份真相各读各的，本类只做聚合，不回写任何一方。</p>
 *
 * <p>聚合口径（与已删除的 {@code NodeStatusBuilder} / {@code MachineSnapshot#totalState} 逐条对齐，
 * 第 1 步要求行为不变）：</p>
 * <ol>
 *   <li>{@code probed}：全部成员都探过才算探过；</li>
 *   <li>{@code stale}：任一成员过期就算过期；</li>
 *   <li>{@code health}：全在线＝在线、部分在线＝缺机、全离线＝失联；</li>
 *   <li>{@code activity}：没规则＝无规则；有人堵塞＝输入堵塞；有人在干活＝运行；有人缺料＝
 *       仓库缺料；有人可输入＝可输入；其余都在空闲＝空闲；否则未知。离线成员不参与这一步；</li>
 *   <li>规则速率取成员求和，规则原因取严重度最大的一个。</li>
 * </ol>
 *
 * <p>过渡位置：第 1–3 步暂放 {@code factory} 包，第 3c 步旧构建器删除后移入 {@code view}，
 * 依赖方向固定为 {@code view → factory/io/runtime}。</p>
 */
public final class ViewTableBuilder {
    /**
     * 输入堵塞的宽限：距上次成功输入超过这么久才报"堵塞"。
     *
     * <p>刚接上机器的一小段时间里输入本来就没成功过，立刻报堵塞会让所有新节点闪一下红。
     * 这个值与旧 {@code MachineSnapshot#totalState} 完全一致（200 tick），迁移期不允许改。</p>
     */
    private static final long INPUT_BLOCKED_GRACE_TICKS = 200L;

    private ViewTableBuilder() {
        // 纯静态组装
    }

    /**
     * 组装当前时刻的整张视图表。
     *
     * @param factory      工厂；调用方保证非空
     * @param observations 观测读取；{@code null} 时所有方块按未探测处理
     * @param form         本 tick 的表单；{@code null} 时没有执行数据
     * @param liveNodes    活节点库；{@code null} 时待投递按空处理
     * @param now          当前服务端 tick
     * @return 视图表
     */
    public static ViewTable build(Factory factory, @Nullable ObservationReader observations,
            @Nullable IoForm form, @Nullable LiveNodeApply liveNodes, long now) {
        BlockFacts.Source facts = observations == null ? key -> BlockFacts.UNKNOWN : observations;
        long healthNow = observations == null ? 0L : observations.currentTick();
        long staleTicks = IdleFactoryConfig.staleHealthTicks();
        boolean storageOnline = factory.storageOnline();
        List<Node> configured = factory.config().nodes().all();
        List<ViewNode> rows = new ArrayList<>(configured.size());
        for (Node node : configured) {
            rows.add(buildNode(factory, node, rows.size(), facts, healthNow, staleTicks,
                    observations, form, liveNodes, now, storageOnline));
        }
        return new ViewTable(new ViewHeader(true, storageOnline), rows);
    }

    /**
     * 组装一个节点行。
     *
     * <p>顺序固定：先按机器算事实（健康、原因、速率），再聚合规则与节点活动。先机器后规则
     * 是因为规则的原因与速率来自成员条目，反过来算会把成员事实遍历两遍。</p>
     */
    private static ViewNode buildNode(Factory factory, Node node, int order,
            BlockFacts.Source facts, long healthNow, long staleTicks,
            @Nullable ObservationReader observations, @Nullable IoForm form,
            @Nullable LiveNodeApply liveNodes, long now, boolean storageOnline) {
        NodeHealthResolver.Snapshot health = NodeHealthResolver.resolve(node, facts, healthNow,
                staleTicks);
        List<ViewMachine> machines = new ArrayList<>(node.machineCount());
        List<MachineFacts> machineFacts = new ArrayList<>(node.machineCount());
        int machineOrder = 0;
        for (Machine machine : node.machines()) {
            NodeHealthResolver.MachineState state = health.machine(machine.id());
            MachineHealth machineHealth = state == null ? MachineHealth.OFFLINE : state.health();
            boolean probed = state != null && state.probed();
            boolean stale = state != null && state.stale();
            BlockFacts observation = observations == null
                    ? BlockFacts.UNKNOWN : observations.at(machine.block());
            machines.add(new ViewMachine(machine.id(), machine.block(), observation.blockId(),
                    machineHealth, probed, stale, machineOrder++));
            machineFacts.add(machineFacts(factory, node, machine, form, observations, now,
                    machineHealth, stale));
        }
        List<ViewIoRule> rules = rules(node, machineFacts);
        Activity activity = aggregate(node.ruleCount(), health.health(),
                memberActivities(node, machineFacts));
        LiveNode runtime = liveNodes == null ? null : liveNodes.node(node.id());
        NodeDispatchQueue queue = runtime == null ? null : runtime.pendingQueue();
        NodeDispatchOrder head = queue == null ? null : queue.first();
        int pendingCount = queue == null ? 0 : queue.size();
        long pendingOldestAt = head == null ? -1L : head.enqueuedAt();
        boolean autoEnergyOnly = node.io().outputs().isEmpty()
                && node.io().inputs().stream().allMatch(spec ->
                        AeKeyAdapter.isEnergy(spec.key()) && spec.face() == null);
        boolean available = storageOnline && health.health() != NodeHealth.OFFLINE;
        ViewNodeCore core = new ViewNodeCore(node.id(), node.name(), node.typeBlockId(), order,
                health.probed(), health.stale(), health.health(), activity,
                node.patternCount(), pendingCount, autoEnergyOnly, pendingOldestAt, available,
                node.provider().blocking(), node.provider().multiplier(),
                node.provider().dispatch());
        return new ViewNode(core, rules, machines);
    }

    /**
     * 一台机器这一轮的事实：健康、逐规则原因与速率、输入侧是否全堵、距上次成功输入多久。
     *
     * <p>只作本类聚合的中间值，不进入视图表——界面没有"单台机器在干什么"的展示需求
     * （草稿 23），多传一层只会让报文与客户端再长出一套聚合口径。</p>
     *
     * @param health               机器健康
     * @param stale                健康是否过期
     * @param workState            观测到的工作状态
     * @param inputReasons         与输入规则顺序一致的原因
     * @param outputReasons        与输出规则顺序一致的原因
     * @param inputRates           与输入规则顺序一致的速率
     * @param outputRates          与输出规则顺序一致的速率
     * @param inputFull            输入侧的规则是否全部"进不去"
     * @param sinceInputMoveTicks  距上次成功输入的 tick；{@code -1} 表示从没成功过
     */
    private record MachineFacts(MachineHealth health, boolean stale,
            WorkState workState, List<IoReason> inputReasons, List<IoReason> outputReasons,
            List<Long> inputRates, List<Long> outputRates, boolean inputFull,
            long sinceInputMoveTicks) {

        MachineFacts {
            inputReasons = List.copyOf(inputReasons);
            outputReasons = List.copyOf(outputReasons);
            inputRates = List.copyOf(inputRates);
            outputRates = List.copyOf(outputRates);
        }
    }

    /**
     * 算一台机器这一轮的事实。
     *
     * <p>原因取"界面显示原因"（{@link PassivePlanner#displayReason}）：退避中的空白轮报休息，
     * 不把上一轮的结果当成这一轮的结果。速率来自每秒采样的统计，机器没统计时按 0。</p>
     */
    private static MachineFacts machineFacts(Factory factory, Node node, Machine machine,
            @Nullable IoForm form, @Nullable ObservationReader observations, long now,
            MachineHealth machineHealth, boolean stale) {
        IoForm.Slot slot = form == null ? null : form.slotOrNull(machine.id());
        IoAnalyzer.Stats.MachineStats stats = factory.analyzer().stats().machineOrNull(machine.id());
        List<ResourceSpec> inputs = node.io().inputs();
        List<ResourceSpec> outputs = node.io().outputs();
        List<IoReason> inputReasons = new ArrayList<>(inputs.size());
        List<Long> inputRates = new ArrayList<>(inputs.size());
        long lastInputSuccessAt = -1L;
        for (ResourceSpec spec : inputs) {
            IoKey key = new IoKey(IoSide.INPUT, spec.key(), spec.face(), spec.mode());
            IoForm.Entry entry = slot == null ? null : slot.entry(key);
            inputReasons.add(entry == null
                    ? IoReason.NO_MATERIAL : PassivePlanner.displayReason(entry, now));
            inputRates.add(stats == null ? 0L : stats.rateIn(key));
            if (entry != null) {
                lastInputSuccessAt = Math.max(lastInputSuccessAt,
                        entry.conclusion().lastSuccessAt());
            }
        }
        List<IoReason> outputReasons = new ArrayList<>(outputs.size());
        List<Long> outputRates = new ArrayList<>(outputs.size());
        for (ResourceSpec spec : outputs) {
            IoKey key = new IoKey(IoSide.OUTPUT, spec.key(), spec.face(), spec.mode());
            IoForm.Entry entry = slot == null ? null : slot.entry(key);
            outputReasons.add(entry == null
                    ? IoReason.NO_MATERIAL : PassivePlanner.displayReason(entry, now));
            outputRates.add(stats == null ? 0L : stats.rateOut(key));
        }
        boolean inputFull = !inputReasons.isEmpty()
                && inputReasons.stream().allMatch(ViewTableBuilder::isInputBlocking);
        long sinceInputMoveTicks = lastInputSuccessAt < 0L
                ? -1L : Math.max(0L, now - lastInputSuccessAt);
        WorkState workState = observations == null
                ? WorkState.UNKNOWN : observations.workState(machine.block());
        return new MachineFacts(machineHealth, stale, workState, inputReasons, outputReasons, inputRates, outputRates, inputFull,
                sinceInputMoveTicks);
    }

    /**
     * 这个原因算不算"进不去"。
     *
     * <p>满、拒收、探不到面都表示这一轮没送进去；缺料是仓库的事，不算机器堵，所以这里不包括
     * {@code NO_MATERIAL}——这与旧聚合的判定完全一致。</p>
     */
    private static boolean isInputBlocking(IoReason reason) {
        return reason == IoReason.FULL || reason == IoReason.REJECTED
                || reason == IoReason.NO_FACE;
    }

    /**
     * 每个成员在干什么：把机器事实压成一档，供 {@link #aggregate} 汇总。
     *
     * @param node  节点（取规则总数）
     * @param facts 成员事实，顺序与节点机器列表一致
     * @return 每个成员的活动
     */
    private static List<Activity> memberActivities(Node node, List<MachineFacts> facts) {
        boolean hasInputs = !node.io().inputs().isEmpty();
        int ruleCount = node.ruleCount();
        List<Activity> activities = new ArrayList<>(facts.size());
        for (MachineFacts machine : facts) {
            activities.add(machineActivity(ruleCount, machine.health(), machine.stale(),
                    hasInputs, machine.inputFull(), machine.sinceInputMoveTicks(),
                    machine.workState(), worst(machine.inputReasons()),
                    worst(machine.outputReasons())));
        }
        return activities;
    }

    /**
     * 聚合节点的规则行：速率取成员求和，原因取最严重的一个。
     *
     * <p>{@code order} 是同方向内的配置顺序（输入、输出各自从 0 开始）：界面按方向分两列排，
     * 用"方向 + 顺序"就足够定位，不需要一个跨方向的全局序号。</p>
     */
    private static List<ViewIoRule> rules(Node node, List<MachineFacts> facts) {
        List<ResourceSpec> inputs = node.io().inputs();
        List<ResourceSpec> outputs = node.io().outputs();
        List<ViewIoRule> rules = new ArrayList<>(inputs.size() + outputs.size());
        for (int index = 0; index < inputs.size(); index++) {
            rules.add(rule(IoSide.INPUT, inputs.get(index), index, facts, index, true));
        }
        for (int index = 0; index < outputs.size(); index++) {
            rules.add(rule(IoSide.OUTPUT, outputs.get(index), index, facts, index, false));
        }
        return rules;
    }

    /**
     * 聚合一条规则在全部成员机器上的速率与原因。
     *
     * @param side  方向
     * @param spec  规则
     * @param order 同方向内的配置顺序
     * @param facts 成员事实，顺序与节点机器列表一致
     * @param index 这条规则在同方向列表里的下标
     * @param input 是否输入侧（决定读哪一列速率与原因）
     */
    private static ViewIoRule rule(IoSide side, ResourceSpec spec, int order,
            List<MachineFacts> facts, int index, boolean input) {
        long rate = 0L;
        IoReason worst = IoReason.RUN;
        for (MachineFacts machine : facts) {
            List<Long> rates = input ? machine.inputRates() : machine.outputRates();
            if (index < rates.size()) {
                rate += rates.get(index);
            }
            List<IoReason> reasons = input ? machine.inputReasons() : machine.outputReasons();
            if (index < reasons.size() && reasons.get(index).severity() > worst.severity()) {
                worst = reasons.get(index);
            }
        }
        return new ViewIoRule(side, spec.key(), spec.face(), spec.mode(), order, rate, worst);
    }

    /**
     * 聚合节点活动：按"最需要管的那台"取一档。
     *
     * <p>与旧 {@code NodeStatusBuilder.resolve} 逐条一致：堵塞 &gt; 运行 &gt; 缺料 &gt; 可输入
     * &gt; 空闲 &gt; 未知；全员离线直接给离线，不把"离线 + 未知"的组合丢给下游。离线成员的
     * 活动一定是 {@code OFFLINE}，进不了任何一档，所以这里不必再按健康过滤一遍。</p>
     *
     * @param ruleCount 节点规则总数
     * @param health    节点健康
     * @param members   每个成员的活动，顺序与节点机器列表一致
     * @return 节点活动
     */
    static Activity aggregate(int ruleCount, NodeHealth health, List<Activity> members) {
        if (members.isEmpty() || health == NodeHealth.OFFLINE) {
            return Activity.OFFLINE;
        }
        if (ruleCount == 0) {
            return Activity.UNCONFIGURED;
        }
        boolean blocked = false;
        boolean running = false;
        boolean waitingMaterial = false;
        boolean inputOk = false;
        boolean idle = false;
        for (Activity member : members) {
            switch (member) {
                case INPUT_BLOCKED -> blocked = true;
                case RUNNING -> running = true;
                case WAITING_MATERIAL -> waitingMaterial = true;
                case INPUT_OK -> inputOk = true;
                case IDLE -> idle = true;
                case OFFLINE, UNCONFIGURED, UNKNOWN -> {
                    // 离线成员不参与"在干什么"的判定；无规则与未知也没有可比信息。
                }
            }
        }
        if (blocked) {
            return Activity.INPUT_BLOCKED;
        }
        if (running) {
            return Activity.RUNNING;
        }
        if (waitingMaterial) {
            return Activity.WAITING_MATERIAL;
        }
        if (inputOk) {
            return Activity.INPUT_OK;
        }
        if (idle) {
            return Activity.IDLE;
        }
        return Activity.UNKNOWN;
    }

    /**
     * 一台机器在干什么：健康优先，然后按输入能力回退。
     *
     * <p>与旧 {@code MachineSnapshot#totalState} 逐条一致，包括"输出满不算缺料"这一条——
     * 缺料是"等仓库"，不是"机器不收"。参数全部是已经算好的纯值，把"取事实"与"下结论"
     * 分开，这条判定才能脱离世界对象单独核对。</p>
     *
     * @param ruleCount            节点规则总数（成员共用）
     * @param health               机器健康
     * @param stale                健康是否过期
     * @param hasInputs            有没有输入规则
     * @param inputFull            输入侧规则是否全部"进不去"
     * @param sinceInputMoveTicks  距上次成功输入的 tick；{@code -1} 表示从没成功过
     * @param workState            观测到的工作状态
     * @param inputReason          输入侧最坏原因
     * @param outputReason         输出侧最坏原因
     * @return 机器活动
     */
    static Activity machineActivity(int ruleCount, MachineHealth health, boolean stale,
            boolean hasInputs, boolean inputFull, long sinceInputMoveTicks,
            WorkState workState, IoReason inputReason, IoReason outputReason) {
        if (!health.isOnline() || stale) {
            return Activity.OFFLINE;
        }
        if (ruleCount == 0) {
            return Activity.UNCONFIGURED;
        }
        if (hasInputs && inputFull
                && (sinceInputMoveTicks < 0L
                        || sinceInputMoveTicks > INPUT_BLOCKED_GRACE_TICKS)) {
            return Activity.INPUT_BLOCKED;
        }
        if (workState == WorkState.WORKING) {
            return Activity.RUNNING;
        }
        if (hasInputs
                && inputReason == IoReason.NO_MATERIAL
                && outputReason != IoReason.FULL
                && outputReason != IoReason.REJECTED) {
            return Activity.WAITING_MATERIAL;
        }
        if (workState == WorkState.IDLE) {
            return Activity.IDLE;
        }
        if (!hasInputs) {
            return Activity.UNKNOWN;
        }
        return Activity.INPUT_OK;
    }

    /**
     * 一列原因里最需要注意的那个（按 {@link IoReason#severity()}）。
     *
     * @param reasons 原因列表
     * @return 最坏原因；空列表按正常
     */
    private static IoReason worst(List<IoReason> reasons) {
        IoReason worst = IoReason.RUN;
        for (IoReason reason : reasons) {
            if (reason.severity() > worst.severity()) {
                worst = reason;
            }
        }
        return worst;
    }
}
