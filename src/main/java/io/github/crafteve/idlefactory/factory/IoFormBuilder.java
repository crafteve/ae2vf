package io.github.crafteve.idlefactory.factory;

import java.util.HashMap;

import org.jetbrains.annotations.Nullable;
import java.util.Map;
import java.util.UUID;

import io.github.crafteve.idlefactory.io.IoForm;
import io.github.crafteve.idlefactory.machine.Machine;
import io.github.crafteve.idlefactory.machine.MachineStateLibrary;
import io.github.crafteve.idlefactory.machine.MachineStatus;
import io.github.crafteve.idlefactory.machine.ResourceSpec;
import io.github.crafteve.idlefactory.node.Node;

/**
 * 生成每 tick 的 IO 表单：世界事实（机器状态）+ 配置事实（节点的机器与规则）→ 工作清单。
 *
 * <p>放在工厂编排包是有意为之：它是「观测 + 配置」唯一的汇合点。放进 {@code io} 会让执行层
 * 反过来依赖节点包，写入口就再也放不进执行层了。</p>
 *
 * <p>过滤条件只有两条：机器状态必须是 {@link MachineStatus#RUN}；机器必须配了规则。不在表上
 * 的机器，执行层不认识它，也不会为它产生任何「不搬」的原因。</p>
 */
public final class IoFormBuilder {
    /** 每个工厂复用一张表单，避免每 tick 新建集合。 */
    private final Map<UUID, IoForm> forms = new HashMap<>();

    /**
     * 生成一个工厂本 tick 的表单。
     *
     * @param factory 工厂
     * @param states  机器状态集合
     * @return 表单；调用方只读
     */
    public IoForm build(Factory factory, MachineStateLibrary states) {
        IoForm form = this.forms.computeIfAbsent(factory.id(), id -> new IoForm());
        form.beginRebuild();
        for (Node node : factory.config().nodes().all()) {
            for (Machine machine : node.machines()) {
                if (states.status(machine.id()) != MachineStatus.RUN) {
                    continue;
                }
                IoForm.Slot slot = form.slot(machine);
                for (ResourceSpec spec : node.io().inputs()) {
                    slot.addInput(spec);
                }
                for (ResourceSpec spec : node.io().outputs()) {
                    slot.addOutput(spec);
                }
            }
        }
        form.endRebuild();
        return form;
    }

    /**
     * 取一个工厂最近一次生成的表单。
     *
     * @param factoryId 工厂 id
     * @return 表单；还没生成过时为 {@code null}
     */
    public @Nullable IoForm form(UUID factoryId) {
        return this.forms.get(factoryId);
    }

    /**
     * 丢掉一个工厂的表单（工厂删除或服务器停止时）。
     *
     * @param factoryId 工厂 id
     */
    public void forget(UUID factoryId) {
        this.forms.remove(factoryId);
    }

    /** 清空全部表单。 */
    public void clear() {
        this.forms.clear();
    }
}
