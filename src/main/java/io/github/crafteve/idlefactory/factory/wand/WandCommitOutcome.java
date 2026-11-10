package io.github.crafteve.idlefactory.factory.wand;

import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.location.BlockKey;

/**
 * 一次绑定杖操作的回执。
 *
 * <p>1:1 连接 / 断开、草稿提交与连锁失败都共用这一份结构：{@code ok} 与
 * {@code failure == NONE} 必须一致，{@code addedMachines / removedMachines} 供 HUD 报数，
 * {@code rejected} 只带前 {@link WandLimits#MAX_ISSUES_WIRE} 个出问题的方块，完整条数在
 * {@code rejectedCount}。</p>
 *
 * @param ok             是否成功
 * @param failure        失败原因；成功时是 {@link WandFailure#NONE}
 * @param reason         删除被拒时的残留配置；其余情况是 {@link WandDeleteReason#NONE}
 * @param nodeId         落到哪个节点；失败时为 {@code null}
 * @param addedMachines  新增机器数
 * @param removedMachines 删除机器数
 * @param rejected       出问题的方块，线上截断
 * @param rejectedCount  出问题的方块总数
 */
public record WandCommitOutcome(boolean ok, WandFailure failure, WandDeleteReason reason,
        @Nullable UUID nodeId, int addedMachines, int removedMachines,
        List<BlockKey> rejected, int rejectedCount) {

    public WandCommitOutcome {
        rejected = List.copyOf(rejected);
        if (ok != (failure == WandFailure.NONE)) {
            throw new IllegalArgumentException("成功标志与失败原因不一致");
        }
        if (failure == WandFailure.DELETE_BLOCKED) {
            if (reason == WandDeleteReason.NONE) {
                throw new IllegalArgumentException("删除被拒必须带原因");
            }
        } else if (reason != WandDeleteReason.NONE) {
            throw new IllegalArgumentException("只有删除被拒才带原因");
        }
        if (rejectedCount < rejected.size() || rejected.size() > WandLimits.MAX_ISSUES_WIRE) {
            throw new IllegalArgumentException("回执问题条数不合法");
        }
        if (addedMachines < 0 || removedMachines < 0) {
            throw new IllegalArgumentException("回执机器数不能为负");
        }
    }

    /**
     * 单原因失败。
     *
     * @param failure 失败原因
     * @return 失败回执
     */
    public static WandCommitOutcome failure(WandFailure failure) {
        return failure(failure, WandDeleteReason.NONE, List.of(), 0);
    }

    /**
     * 带删除原因与问题方块的失败。
     *
     * @param failure       失败原因
     * @param reason        删除被拒时的残留配置
     * @param rejected      出问题的方块，最多 {@link WandLimits#MAX_ISSUES_WIRE} 个
     * @param rejectedCount 出问题的方块总数
     * @return 失败回执
     */
    public static WandCommitOutcome failure(WandFailure failure, WandDeleteReason reason,
            List<BlockKey> rejected, int rejectedCount) {
        return new WandCommitOutcome(false, failure, reason, null, 0, 0, rejected, rejectedCount);
    }

    /**
     * 成功回执。
     *
     * @param nodeId          落到的节点
     * @param addedMachines   新增机器数
     * @param removedMachines 删除机器数
     * @return 成功回执
     */
    public static WandCommitOutcome success(UUID nodeId, int addedMachines, int removedMachines) {
        return new WandCommitOutcome(true, WandFailure.NONE, WandDeleteReason.NONE, nodeId,
                addedMachines, removedMachines, List.of(), 0);
    }
}
