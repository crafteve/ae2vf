package io.github.crafteve.idlefactory.factory;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.storage.StorageMode;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * 工厂行政窗口：保证工厂存在、按 id 删除节点/机器、机器与仓库的 toggle、绑定草稿提交。
 *
 * <p>指令删除后，这里只剩绑定杖、网络包与界面真正会走的入口；建厂不再是玩家动作，
 * 打开终端时由 {@link #ensureFactory} 静默完成。</p>
 *
 * <p>它只做装配与转发，业务实现分别在 {@link BindingService}（草稿提交与整组 toggle）、
 * {@link MachineLinkService}（按 id 删除）、{@link StorageLinkService}（仓库 toggle 与模式切换）。</p>
 */
public final class FactoryService {
    private FactoryService() {
        // 纯静态工具类
    }

    /**
     * 保证玩家有工厂：没有就静默创建，已有就不动。
     *
     * <p>终端菜单打开前调它。创建用的是与旧生命周期服务相同的确定性 id 规则
     * （owner + 自增序号），只是不再有"创建成功"的聊天回执。</p>
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
        runtime.markDirty();
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
     * 按机器 id 删除；界面确认后走这个入口。
     *
     * @param factory   工厂
     * @param machineId 机器 id
     * @return 动作结果
     */
    public static NodeActions.Result deleteMachineById(Factory factory, UUID machineId) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return NodeActions.Result.err("Idle Factory 尚未启动（服务端未就绪）");
        }
        return machineLinks(runtime).deleteMachineById(factory, machineId);
    }

    /**
     * 提交一份绑定草稿；绑定杖客户端只发这一个入口。
     *
     * @param factory 工厂
     * @param draft   完整草稿
     * @return 提交结果
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
     * 一次草稿提交的结果；与 {@code BindingService.BindingResult} 同形，供网络包直接使用。
     *
     * @param ok       是否成功
     * @param nodeId   落到哪个节点；失败时为 {@code null}
     * @param machines 本次新增机器数
     * @param message  给玩家看的一句话
     */
    public record BindingResult(boolean ok, java.util.UUID nodeId, int machines, String message) {
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
     * 机器 toggle：已连接就断开，未连接就连接；连锁按整组 toggle。
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
        return new NodeLibraryService(runtime::markDirty);
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
     * 装配机器整组 toggle 服务。
     *
     * @param runtime 运行时
     * @return 服务实例
     */
    private static BindingService machineToggle(FactoryRuntime runtime) {
        return new BindingService(runtime.manager(), runtime.observations(),
                machineLibrary(runtime), machineLinks(runtime), runtime.server());
    }

    /**
     * 装配仓库接线服务。
     *
     * @param runtime 运行时
     * @return 服务实例
     */
    private static StorageLinkService storageLinks(FactoryRuntime runtime) {
        return new StorageLinkService(runtime.manager(), runtime::markDirty,
                runtime.storageResolver());
    }
}
