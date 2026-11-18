package io.github.crafteve.idlefactory.factory;

import java.util.List;
import java.util.UUID;

import io.github.crafteve.idlefactory.command.CommandTopology;
import io.github.crafteve.idlefactory.location.BlockKey;
import io.github.crafteve.idlefactory.factory.wand.WandCommitOutcome;
import io.github.crafteve.idlefactory.factory.wand.WandDraft;
import io.github.crafteve.idlefactory.factory.wand.WandFailure;
import io.github.crafteve.idlefactory.factory.wand.WandIssue;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * 工厂行政窗口：保证工厂存在、按 id 删除节点、绑定杖的四个入口与仓库 toggle。
 *
 * <p>指令删除后，这里只剩绑定杖、网络包与界面真正会走的入口；建厂不再是玩家动作，
 * 打开终端时由 {@link #ensureFactory} 静默完成，并顺手推一次链接快照。</p>
 *
 * <p>它只做装配与转发，业务实现分别在 {@link WandService}（绑定杖判定与写库）、
 * {@code command.CommandTopology}（节点拓扑写入）与统一写命令（规则、仓库锚点）。
 * 绑定杖回执直接复用 {@code factory.wand} 的公共 record，不引入网络层的类型。</p>
 */
public final class FactoryService {
    private FactoryService() {
        // 纯静态工具类
    }

    /**
     * 连锁查找结果。
     *
     * @param machines 过滤后可加入的机器方块
     * @param failure  失败原因；成功时为 {@link WandFailure#NONE}
     */
    public record ChainResult(List<BlockKey> machines, WandFailure failure) {
        public ChainResult {
            machines = List.copyOf(machines);
        }
    }

    /**
     * 打开面板前的预校验结果。
     *
     * @param issues     逐块与草稿级问题，完整列表
     * @param issueCount 问题总数
     * @param failure    不打开面板的草稿级原因；可以打开时为 {@link WandFailure#NONE}
     */
    public record OpenResult(List<WandIssue> issues, int issueCount, WandFailure failure) {
        public OpenResult {
            issues = List.copyOf(issues);
        }
    }

    /**
     * 编辑态 Ctrl + 右键：查找同 id 机器群。
     *
     * @param player 发起请求的玩家
     * @param origin 连锁起点
     * @return 机器列表与失败原因
     */
    public static ChainResult chainMachines(ServerPlayer player, BlockKey origin) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return new ChainResult(List.of(), WandFailure.INVALID_REQUEST);
        }
        return wandService(runtime).chain(player, origin);
    }

    /**
     * 编辑态蹲下右键：只读预校验，供面板标红。
     *
     * @param player 发起请求的玩家
     * @param draft  当前草稿
     * @return 预校验结果
     */
    public static OpenResult openDraft(ServerPlayer player, WandDraft draft) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return new OpenResult(List.of(), 0, WandFailure.INVALID_REQUEST);
        }
        return wandService(runtime).open(player, draft);
    }

    /**
     * 面板点确定：提交整份草稿。
     *
     * <p>提交序号只在成功后推进：失败时同一份草稿可以原样重试，不会被误判成重放。</p>
     *
     * @param player 发起请求的玩家
     * @param draft  完整草稿
     * @return 回执
     */
    public static WandCommitOutcome commitDraft(ServerPlayer player, WandDraft draft) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return WandCommitOutcome.failure(WandFailure.INVALID_REQUEST);
        }
        long lastAcceptedSeq = runtime.lastAcceptedSeq(player.getUUID());
        WandCommitOutcome outcome = wandService(runtime).commit(player, draft, lastAcceptedSeq);
        if (outcome.ok()) {
            runtime.acceptCommitSeq(player.getUUID(), draft.requestSeq());
        }
        return outcome;
    }

    /**
     * 服务端未就绪时的统一回执。
     *
     * @param player 玩家
     * @return {@code 0}
     */
    private static int notStarted(ServerPlayer player) {
        FactoryMessages.err(player, "Idle Factory 尚未启动（服务端未就绪）");
        return 0;
    }

    /**
     * 装配节点拓扑写入口。
     *
     * @param runtime 运行时
     * @return 服务实例
     */
    private static CommandTopology topology(FactoryRuntime runtime) {
        return new CommandTopology(runtime, runtime.nodeManager(), runtime.nodeIoRunner(),
                runtime.server());
    }

    /**
     * 装配绑定杖服务。
     *
     * @param runtime 运行时
     * @return 服务实例
     */
    private static WandService wandService(FactoryRuntime runtime) {
        return new WandService(runtime.manager(), runtime.occupancy(), runtime.observations(),
                topology(runtime), runtime.server());
    }

}
