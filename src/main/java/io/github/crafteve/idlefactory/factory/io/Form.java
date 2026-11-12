package io.github.crafteve.idlefactory.factory.io;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.machine.ResourceSpec;

/**
 * 本 tick 的唯一工作清单：每台在线机器各自的一组输入 / 输出条目。
 *
 * <p>不在表上的条目，执行层根本不认识它。表单由「世界事实 + 配置」生成，只收
 * {@code MachineStatus.RUN} 且配了规则的机器；机器一旦离开 RUN，它的条目连同账本与结论一起
 * 被释放（重新进入从零开始）。</p>
 *
 * <p>槽位与条目都跨 tick 复用：每 tick 只做 mark-seen / add / drop，不新建集合。条目身份由
 * {@link Entry.Key}（机器 + 规则）唯一确定，规则的面或匹配模式变了就视为新条目。</p>
 */
public final class Form {
    /** 机器 id → 槽位；插入顺序即配置顺序，执行时按它轮转。 */
    private final Map<UUID, Slot> slots = new LinkedHashMap<>();
    /** 本轮出现过的机器 id。 */
    private final Set<UUID> seen = new HashSet<>();
    /** 本轮按配置顺序排好的槽位，执行层直接遍历。 */
    private final List<Slot> ordered = new ArrayList<>();

    /** 开始重建：清空上一轮的顺序，保留可复用对象。 */
    public void beginRebuild() {
        this.seen.clear();
        this.ordered.clear();
    }

    /**
     * 取一台机器的槽位（没有就建），并开始这一轮的清点。
     *
     * @param machine 机器
     * @return 槽位
     */
    public Slot slot(Machine machine) {
        this.seen.add(machine.id());
        Slot slot = this.slots.get(machine.id());
        if (slot == null) {
            slot = new Slot(machine);
            this.slots.put(machine.id(), slot);
        }
        slot.beginRebuild();
        this.ordered.add(slot);
        return slot;
    }

    /** 结束重建：释放本轮没出现的机器槽位与槽位里没出现的条目。 */
    public void endRebuild() {
        for (Slot slot : this.slots.values()) {
            slot.endRebuild();
        }
        this.slots.keySet().removeIf(id -> !this.seen.contains(id));
    }

    /** 本轮的工作槽位，顺序与配置一致。 */
    public List<Slot> slots() {
        return this.ordered;
    }

    /**
     * 按机器查槽位。
     *
     * @param machineId 机器 id
     * @return 槽位；这台机器不在表单上时为 {@code null}
     */
    public @Nullable Slot slotOrNull(UUID machineId) {
        return this.slots.get(machineId);
    }

    /**
     * 一台机器的槽位：输入 / 输出各一组，执行时按顺序遍历。
     */
    public static final class Slot {
        private final Machine machine;
        /** 条目池：身份 → 条目，跨 tick 复用。 */
        private final Map<Entry.Key, Entry> pool = new HashMap<>();
        private final List<Entry> inputs = new ArrayList<>();
        private final List<Entry> outputs = new ArrayList<>();

        Slot(Machine machine) {
            this.machine = machine;
        }

        /** 机器。 */
        public Machine machine() {
            return this.machine;
        }

        /** 输入条目，按配置顺序。 */
        public List<Entry> inputs() {
            return this.inputs;
        }

        /** 输出条目，按配置顺序。 */
        public List<Entry> outputs() {
            return this.outputs;
        }

        /**
         * 按规则查条目。
         *
         * @param rule 规则运行时身份（自带方向）
         * @return 条目；规则不在表单上时为 {@code null}
         */
        public @Nullable Entry entry(RuleKey rule) {
            return this.pool.get(new Entry.Key(this.machine.id(), rule));
        }

        void beginRebuild() {
            this.inputs.clear();
            this.outputs.clear();
            for (Entry entry : this.pool.values()) {
                entry.markUnseen();
            }
        }

        /**
         * 加一条输入规则。
         *
         * @param spec 规则
         */
        public void addInput(ResourceSpec spec) {
            this.inputs.add(this.entry(spec, IoSide.INPUT));
        }

        /**
         * 加一条输出规则。
         *
         * @param spec 规则
         */
        public void addOutput(ResourceSpec spec) {
            this.outputs.add(this.entry(spec, IoSide.OUTPUT));
        }

        void endRebuild() {
            this.pool.values().removeIf(entry -> !entry.seen());
        }

        private Entry entry(ResourceSpec spec, IoSide side) {
            RuleKey rule = new RuleKey(side, spec.key(), spec.face(), spec.mode());
            Entry entry = this.pool.get(new Entry.Key(this.machine.id(), rule));
            if (entry == null) {
                entry = new Entry(new Entry.Key(this.machine.id(), rule), this.machine, spec);
                this.pool.put(entry.key(), entry);
            } else {
                entry.refresh(spec);
            }
            return entry;
        }
    }

    /**
     * 表单上的一行：一台机器的某一条规则（输入或输出）。
     *
     * <p>条目跨 tick 复用：身份不变就复用同一个对象，只刷新内联事实。这有两个原因：</p>
     * <ul>
     *   <li>账本与结论（睡眠、拒收连击、经验面）要跟着条目活，不能每 tick 重置；</li>
     *   <li>1024 台 × 16 条规则的规模下，每 tick 全量新建对象是没必要的开销。</li>
     * </ul>
     *
     * <p><b>数量必须每 tick 刷新</b>：{@link RuleKey} 不含 {@code amount}，玩家只改数量时身份不变，
     * 不刷新就会一直用旧数量搬。</p>
     */
    public static final class Entry {
        /**
         * 条目身份：机器 + 规则。
         *
         * <p>规则归节点、执行归机器，所以身份必须带机器 id：同一条规则作用在 N 台机器上是 N 个条目。
         * {@link RuleKey} 自己带方向，因此这里不再另存一份，避免同一概念写两处。</p>
         *
         * @param machineId 机器 id
         * @param rule      规则运行时身份
         */
        public record Key(UUID machineId, RuleKey rule) {
        }

        private final Key key;
        private final Machine machine;
        /** 本条目的账本（明细，会滚）。 */
        private final Analyzer.Ledger ledger = new Analyzer.Ledger();
        /** 本条目的历史结论（摘要，不滚）。 */
        private final Analyzer.Conclusion conclusion = new Analyzer.Conclusion();
        private ResourceSpec spec;
        /** 本轮是否还在表单上；表单重建后为 {@code false} 的条目会被释放。 */
        private boolean seen = true;

        /**
         * @param key     条目身份
         * @param machine 机器
         * @param spec    规则
         */
        Entry(Key key, Machine machine, ResourceSpec spec) {
            this.key = key;
            this.machine = machine;
            this.spec = spec;
        }

        /** 条目身份。 */
        public Key key() {
            return this.key;
        }

        /** 机器 id。 */
        public UUID machineId() {
            return this.key.machineId();
        }

        /** 机器。 */
        public Machine machine() {
            return this.machine;
        }

        /** 方向。 */
        public IoSide side() {
            return this.key.rule().side();
        }

        /** 当前规则；每 tick 由表单刷新。 */
        public ResourceSpec spec() {
            return this.spec;
        }

        /** 本条目的账本。 */
        public Analyzer.Ledger ledger() {
            return this.ledger;
        }

        /** 本条目的历史结论。 */
        public Analyzer.Conclusion conclusion() {
            return this.conclusion;
        }

        /**
         * 刷新内联事实。
         *
         * @param spec 当前规则
         */
        void refresh(ResourceSpec spec) {
            this.spec = spec;
            this.seen = true;
        }

        /** 表单重建开始时调用：先假设本轮不在表单上。 */
        void markUnseen() {
            this.seen = false;
        }

        /**
         * 本轮是否还在表单上。
         *
         * @return 还在为 {@code true}
         */
        boolean seen() {
            return this.seen;
        }
    }
}
