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
 * 本 tick 的唯一工作清单：每台在线机器各自的一组输入 / 输出单元。
 *
 * <p>不在表上的单元，执行层根本不认识它。表单由「世界事实 + 配置」生成，只收
 * {@code MachineStatus.RUN} 且配了规则的机器；机器一旦离开 RUN，它的单元连同执行结论一起
 * 被释放（重新进入从零开始）。</p>
 *
 * <p>槽位与单元都跨 tick 复用：每 tick 只做 mark-seen / add / drop，不新建集合。</p>
 */
public final class IoForm {
    private final UUID factoryId;
    /** 机器 id → 槽位；插入顺序即配置顺序，执行时按它轮转。 */
    private final Map<UUID, Slot> slots = new LinkedHashMap<>();
    /** 本轮出现过的机器 id。 */
    private final Set<UUID> seen = new HashSet<>();
    /** 本轮按配置顺序排好的槽位，执行层直接遍历。 */
    private final List<Slot> ordered = new ArrayList<>();

    /**
     * @param factoryId 所属工厂
     */
    public IoForm(UUID factoryId) {
        this.factoryId = factoryId;
    }

    /** 所属工厂。 */
    public UUID factoryId() {
        return this.factoryId;
    }

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

    /** 结束重建：释放本轮没出现的机器槽位（连同单元）。 */
    public void endRebuild() {
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

    /** 是否没有活干。 */
    public boolean isEmpty() {
        return this.ordered.isEmpty();
    }

    /**
     * 一台机器的槽位：输入 / 输出各一组，执行时按顺序遍历。
     */
    public static final class Slot {
        private final Machine machine;
        /** 单元池：身份 → 单元，跨 tick 复用。 */
        private final Map<IoUnitKey, IoUnit> pool = new HashMap<>();
        private final List<IoUnit> inputs = new ArrayList<>();
        private final List<IoUnit> outputs = new ArrayList<>();
        private final List<ResourceSpec> inputSpecs = new ArrayList<>();
        private final List<ResourceSpec> outputSpecs = new ArrayList<>();

        Slot(Machine machine) {
            this.machine = machine;
        }

        /** 机器。 */
        public Machine machine() {
            return this.machine;
        }

        /** 输入单元，按配置顺序。 */
        public List<IoUnit> inputs() {
            return this.inputs;
        }

        /** 输出单元，按配置顺序。 */
        public List<IoUnit> outputs() {
            return this.outputs;
        }

        /** 输入规则（与 {@link #inputs()} 一一对应），给执行器直接用。 */
        public List<ResourceSpec> inputSpecs() {
            return this.inputSpecs;
        }

        /** 输出规则（与 {@link #outputs()} 一一对应）。 */
        public List<ResourceSpec> outputSpecs() {
            return this.outputSpecs;
        }

        /**
         * 按规则查单元。
         *
         * @param input 是否输入侧
         * @param key   规则
         * @return 单元；规则不在表单上时为 {@code null}
         */
        public @Nullable IoUnit unit(boolean input, RuleKey key) {
            return this.pool.get(new IoUnitKey(this.machine.id(), input, key));
        }

        void beginRebuild() {
            this.inputs.clear();
            this.outputs.clear();
            this.inputSpecs.clear();
            this.outputSpecs.clear();
            for (IoUnit unit : this.pool.values()) {
                unit.markUnseen();
            }
        }

        /**
         * 加一条输入规则。
         *
         * @param spec 规则
         */
        public void addInput(ResourceSpec spec) {
            IoUnit unit = this.unit(true, spec);
            this.inputs.add(unit);
            this.inputSpecs.add(unit.spec());
        }

        /**
         * 加一条输出规则。
         *
         * @param spec 规则
         */
        public void addOutput(ResourceSpec spec) {
            IoUnit unit = this.unit(false, spec);
            this.outputs.add(unit);
            this.outputSpecs.add(unit.spec());
        }

        void endRebuild() {
            this.pool.values().removeIf(unit -> !unit.seen());
        }

        private IoUnit unit(boolean input, ResourceSpec spec) {
            IoUnitKey key = new IoUnitKey(this.machine.id(), input,
                    new RuleKey(input, spec.key(), spec.face(), spec.mode()));
            IoUnit unit = this.pool.get(key);
            if (unit == null) {
                unit = new IoUnit(key, this.machine, input, spec);
                this.pool.put(key, unit);
            } else {
                unit.refresh(spec);
            }
            return unit;
        }
    }
}
