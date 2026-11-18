package io.github.crafteve.idlefactory.view;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.io.IoReason;
import io.github.crafteve.idlefactory.view.MachineSnapshot;
import io.github.crafteve.idlefactory.location.BlockKey;
import io.github.crafteve.idlefactory.machine.MachineHealth;
import io.github.crafteve.idlefactory.machine.ResourceSpec;
import io.github.crafteve.idlefactory.observation.WorkState;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

public record MachineSnapshot(UUID nodeId, @Nullable String nodeName,
        @Nullable ResourceLocation nodeIconBlockId, int nodeMachineCount,
        UUID machineId, BlockKey primaryBlock,
        MachineHealth health, boolean probed, boolean stale,
        @Nullable ResourceLocation blockId, @Nullable ResourceLocation groupBlockId,
        @Nullable ResourceLocation beTypeId, int ruleCount,
        List<ResourceSpec> inputs, List<ResourceSpec> outputs,
        List<Long> inputRates, List<Long> outputRates,
        long sinceMoveTicks, long blockedTicks,
        WorkState workState, boolean inputFull, long sinceInputMoveTicks,
        IoReason inputReason, IoReason outputReason,
        List<IoReason> inputReasons, List<IoReason> outputReasons) {

    public MachineSnapshot {
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        inputRates = List.copyOf(inputRates);
        outputRates = List.copyOf(outputRates);
        inputReasons = alignReasons(inputReasons, inputs.size());
        outputReasons = alignReasons(outputReasons, outputs.size());
    }

    /**
     * 把原因列表补齐到规则条数。
     *
     * <p>旧快照或服务端尚未产生本轮结果时列表可能偏短；补 {@link IoReason#NO_MATERIAL}
     * 而不是 {@code OK}，界面才不会把没跑过的规则画成「正常」。</p>
     */
    private static List<IoReason> alignReasons(List<IoReason> reasons, int size) {
        List<IoReason> aligned = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            aligned.add(i < reasons.size() ? reasons.get(i) : IoReason.NO_MATERIAL);
        }
        return List.copyOf(aligned);
    }

    /** 机器所在维度。 */
    public ResourceKey<Level> dimension() {
        return this.primaryBlock.dimension();
    }

    /** 机器类型方块坐标。 */
    public BlockPos pos() {
        return this.primaryBlock.pos();
    }

    /** 是否在线。 */
    public boolean online() {
        return this.health.isOnline();
    }

    /** 输入侧总速率。 */
    public long rateIn() {
        long total = 0L;
        for (long rate : this.inputRates) {
            total += rate;
        }
        return total;
    }

    /** 输出侧总速率。 */
    public long rateOut() {
        long total = 0L;
        for (long rate : this.outputRates) {
            total += rate;
        }
        return total;
    }

    /** 两侧总速率。 */
    public long totalRate() {
        return rateIn() + rateOut();
    }

    /**
     * 取一条规则的速率。
     *
     * @param input 是否输入
     * @param index 规则下标
     * @return 数量/秒；越界时为 0
     */
    public long ruleRate(boolean input, int index) {
        List<Long> rates = input ? this.inputRates : this.outputRates;
        return index >= 0 && index < rates.size() ? rates.get(index) : 0L;
    }

    /**
     * View 层的总状态：健康优先，UNKNOWN 时用 IO 输入能力回退。
     *
     * @return 总状态
     */
    public TotalState totalState() {
        if (!this.health.isOnline() || this.stale) {
            return TotalState.OFFLINE;
        }
        if (this.ruleCount == 0) {
            return TotalState.UNCONFIGURED;
        }
        if (!this.inputs.isEmpty() && this.inputFull
                && (this.sinceInputMoveTicks < 0L || this.sinceInputMoveTicks > 200L)) {
            return TotalState.INPUT_BLOCKED;
        }
        if (this.workState == WorkState.WORKING) {
            return TotalState.RUNNING;
        }
        // 缺料是「等仓库」，不是「机器不收」；输出满了不算缺料，避免误报。
        if (!this.inputs.isEmpty()
                && this.inputReason == IoReason.NO_MATERIAL
                && this.outputReason != IoReason.FULL
                && this.outputReason != IoReason.REJECTED) {
            return TotalState.WAITING_MATERIAL;
        }
        if (this.workState == WorkState.IDLE) {
            return TotalState.IDLE;
        }
        if (this.inputs.isEmpty()) {
            return TotalState.UNKNOWN;
        }
        return TotalState.INPUT_OK;
    }
}
