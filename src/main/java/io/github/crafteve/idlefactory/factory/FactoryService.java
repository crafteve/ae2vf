package io.github.crafteve.idlefactory.factory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.location.BlockKey;
import io.github.crafteve.idlefactory.factory.storage.StorageMode;
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
 * {@link MachineLinkService}（按 id 删除）、{@link StorageLinkService}（仓库 toggle 与模式切换）。
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
     * 保证玩家有工厂：没有就静默创建，已有就不动。
     *
     * <p>终端菜单打开前调它。创建用的是与旧生命周期服务相同的确定性 id 规则
     * （owner + 自增序号），只是不再有「创建成功」的聊天回执。新建工厂要推一次链接快照，
     * 否则绑定杖要等到下一次结构变更才看得到它。</p>
     *
     * @param player 玩家
     */
    public static void ensureFactory(ServerPlayer player) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return;
        }
        FactoryManager manager = runtime.manager();
        if (manager.byOwner(player.getUUID()).isPresent()) {
            return;
        }
        UUID id = nextFactoryId(manager, runtime, player.getUUID());
        Factory factory = new Factory(id,
                new FactoryConfig(player.getUUID(), StorageMode.ME, null));
        if (!manager.add(factory)) {
            IdleFactory.LOGGER.warn("自动创建工厂失败：player={}, id={}", player.getUUID(), id);
            return;
        }
        runtime.markDirtyLinks(factory);
    }

    /**
     * 生成一个不与现有工厂冲突的确定性 id。
     *
     * @param manager 工厂档案柜
     * @param runtime 运行时（取自增序号）
     * @param owner   拥有者
     * @return 新 id
     */
    private static UUID nextFactoryId(FactoryManager manager, FactoryRuntime runtime, UUID owner) {
        UUID id;
        do {
            long seq = runtime.takeNextSeq();
            id = UUID.nameUUIDFromBytes((owner + ":" + seq).getBytes(StandardCharsets.UTF_8));
        } while (manager.byId(id).isPresent());
        return id;
    }

    /**
     * 按节点 id 删除整个节点；节点终端删除入口。
     *
     * @param factory 工厂
     * @param nodeId  节点 id
     * @return 动作结果
     */
    public static NodeActions.Result deleteNodeById(Factory factory, UUID nodeId) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return NodeActions.Result.err("Idle Factory 尚未启动（服务端未就绪）");
        }
        return machineLinks(runtime).deleteNodeById(factory, nodeId);
    }

    /**
     * 旧绑定路径：提交一份旧草稿；随旧客户端一起删除。
     *
     * @param factory 工厂
     * @param draft   旧草稿
     * @return 旧回执
     */
    public static BindingResult commitBinding(Factory factory, BindingDraft draft) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return new BindingResult(false, null, 0, "Idle Factory 尚未启动（服务端未就绪）");
        }
        BindingService.BindingResult result = machineToggle(runtime).commit(factory, draft);
        return new BindingResult(result.ok(), result.nodeId(), result.machines(),
                result.message());
    }

    /**
     * 旧绑定路径的草稿提交结果；随旧客户端一起删除。
     *
     * @param ok       是否成功
     * @param nodeId   落到哪个节点；失败时为 {@code null}
     * @param machines 本次新增机器数
     * @param message  给玩家看的一句话
     */
    public record BindingResult(boolean ok, java.util.UUID nodeId, int machines, String message) {
    }

    /**
     * 旧绑定路径：整组 toggle；随旧客户端一起删除。
     *
     * @param player 发起请求的玩家
     * @param pos    目标机器
     * @param chain  是否连锁
     * @return 变更的台数
     */
    public static int toggleMachines(ServerPlayer player, BlockPos pos, boolean chain) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return notStarted(player);
        }
        return machineToggle(runtime).toggleMachines(player, pos, chain);
    }

    /**
     * 空闲态右键方块：1:1 连接或断开。
     *
     * @param player 发起请求的玩家
     * @param pos    目标方块
     * @return 回执
     */
    public static WandCommitOutcome toggleMachine(ServerPlayer player, BlockPos pos) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return WandCommitOutcome.failure(WandFailure.INVALID_REQUEST);
        }
        return wandService(runtime).toggle(player, pos);
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
     * 切换 ME / 容器模式；有链接时拒绝。
     *
     * @param player 发起请求的玩家
     * @return 成功为 {@code 1}
     */
    public static int switchStorageMode(ServerPlayer player) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return notStarted(player);
        }
        return storageLinks(runtime).switchStorageMode(player);
    }

    /**
     * 仓库 toggle：ME 或容器按当前模式处理；容器连锁按整组 toggle。
     *
     * @param player 发起请求的玩家
     * @param pos    目标方块
     * @param chain  是否连锁
     * @return 变更数量
     */
    public static int toggleStorage(ServerPlayer player, BlockPos pos, boolean chain) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return notStarted(player);
        }
        return storageLinks(runtime).toggleStorage(player, pos, chain);
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
     * 装配机器库写入口。
     *
     * @param runtime 运行时
     * @return 服务实例
     */
    private static NodeLibraryService machineLibrary(FactoryRuntime runtime) {
        return new NodeLibraryService(runtime::markDirtyLinks);
    }

    /**
     * 装配机器接线服务。
     *
     * @param runtime 运行时
     * @return 服务实例
     */
    private static MachineLinkService machineLinks(FactoryRuntime runtime) {
        return new MachineLinkService(runtime.manager(), runtime.nodeManager(),
                runtime.nodeIoRunner(), machineLibrary(runtime), runtime.server());
    }

    /**
     * 装配旧绑定路径的整组 toggle 服务；随旧客户端一起删除。
     *
     * @param runtime 运行时
     * @return 服务实例
     */
    private static BindingService machineToggle(FactoryRuntime runtime) {
        return new BindingService(runtime.manager(), runtime.observations(),
                machineLibrary(runtime), machineLinks(runtime), runtime.server());
    }

    /**
     * 装配绑定杖服务。
     *
     * @param runtime 运行时
     * @return 服务实例
     */
    private static WandService wandService(FactoryRuntime runtime) {
        return new WandService(runtime.manager(), runtime.observations(),
                machineLibrary(runtime), machineLinks(runtime), runtime.server());
    }

    /**
     * 装配仓库接线服务。
     *
     * @param runtime 运行时
     * @return 服务实例
     */
    private static StorageLinkService storageLinks(FactoryRuntime runtime) {
        return new StorageLinkService(runtime.manager(), runtime::markDirtyLinks,
                runtime.storageResolver());
    }
}
