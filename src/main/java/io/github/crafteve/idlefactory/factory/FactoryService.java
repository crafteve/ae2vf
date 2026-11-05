package io.github.crafteve.idlefactory.factory;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 工厂行政窗口：创建 / 删除 / 绑定仓库 / 机器库 / 仓库调试的静态入口。
 *
 * <p>终端、指令、以后的 WebUI 都走这里。它只做装配与转发，权限（第一版只看 owner）与
 * 档案写入在下面几个职责类里：{@link FactoryLifecycleService}（生命周期）、
 * {@link MachineLinkService} / {@link BindingService}（机器接线）、
 * {@link StorageLinkService} / {@link StorageMaintenanceService} /
 * {@link StorageConsoleService}（仓库）。</p>
 *
 * <p>保留静态签名是为了让指令、包处理器与物品入口零改动；新代码应直接用职责类，
 * 不要再通过本类定位运行时。</p>
 */
public final class FactoryService {
    private FactoryService() {
        // 纯静态工具类
    }


    // ------------------------------------------------------------------
    // ---- 静态门面：装配服务实例并转发 ----
    // 保留原有静态签名，调用方零改动；业务实现见 FactoryLifecycleService / MachineLinkService / BindingService。
    // ------------------------------------------------------------------

    /**
     * 保证玩家有工厂：没有就静默创建，已有就不动。
     *
     * <p>终端菜单打开前调它；业务实现已下沉到 {@link FactoryLifecycleService}。</p>
     *
     * @param player 玩家
     */
    public static void ensureFactory(ServerPlayer player) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime != null) {
            lifecycle(runtime).ensureFactory(player);
        }
    }

    /**
     * 给玩家创建一个工厂。一人一个，已有工厂时不动。
     *
     * @param player 发起请求的玩家
     * @return 命令结果（{@code 1} 成功，{@code 0} 未创建）
     */
    public static int create(ServerPlayer player) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return notStarted(player);
        }
        return lifecycle(runtime).create(player);
    }

    /**
     * 删除玩家自己的工厂。机器方块本身不动，只是不再属于任何工厂。
     *
     * @param player 发起请求的玩家
     * @return 命令结果（{@code 1} 成功，{@code 0} 未删除）
     */
    public static int delete(ServerPlayer player) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return notStarted(player);
        }
        return lifecycle(runtime).delete(player);
    }

    /**
     * 打印工厂摘要和机器库。
     *
     * @param player 发起请求的玩家
     * @return 命令结果
     */
    public static int info(ServerPlayer player) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return notStarted(player);
        }
        return lifecycle(runtime).info(player);
    }

    /**
     * 把准星选中的机器接入玩家自己的工厂。
     *
     * @param player 发起请求的玩家
     * @return 命令结果（{@code 1} 成功，{@code 0} 失败）
     */
    public static int connectMachine(ServerPlayer player) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return notStarted(player);
        }
        return machineLinks(runtime).connectMachine(player);
    }

    /**
     * 删除准星选中的机器；有 IO 规则时先要求确认。
     *
     * @param player 发起请求的玩家
     * @return 命令结果
     */
    public static int disconnectMachine(ServerPlayer player) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return notStarted(player);
        }
        return machineLinks(runtime).disconnectMachine(player);
    }

    /**
     * 确认删除准星选中的机器。
     *
     * @param player 发起请求的玩家
     * @return 命令结果
     */
    public static int confirmDisconnectMachine(ServerPlayer player) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return notStarted(player);
        }
        return machineLinks(runtime).confirmDisconnectMachine(player);
    }

    /**
     * 按短 id 删除机器；有 IO 规则时先要求确认。
     *
     * @param player  发起请求的玩家
     * @param shortId 短 id（前 8 位）
     * @return 命令结果
     */
    public static int disconnectMachine(ServerPlayer player, String shortId) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return notStarted(player);
        }
        return machineLinks(runtime).disconnectMachine(player, shortId);
    }

    /**
     * 按短 id 确认删除机器。
     *
     * @param player  发起请求的玩家
     * @param shortId 短 id（前 8 位）
     * @return 命令结果
     */
    public static int confirmDisconnectMachine(ServerPlayer player, String shortId) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return notStarted(player);
        }
        return machineLinks(runtime).confirmDisconnectMachine(player, shortId);
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
            return new BindingResult(false, null, 0, 0, "Idle Factory 尚未启动（服务端未就绪）");
        }
        BindingService.BindingResult result = machineToggle(runtime).commit(factory, draft);
        return new BindingResult(result.ok(), result.nodeId(), result.machines(), result.blocks(),
                result.message());
    }

    /**
     * 一次草稿提交的结果；与 {@code BindingService.BindingResult} 同形，供网络包直接使用。
     *
     * @param ok       是否成功
     * @param nodeId   落到哪个节点；失败时为 {@code null}
     * @param machines 本次新增机器数
     * @param blocks   本次新增方块数
     * @param message  给玩家看的一句话
     */
    public record BindingResult(boolean ok, java.util.UUID nodeId, int machines, int blocks,
            String message) {
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
                runtime.nodeIoRunner(), machineLibrary(runtime), runtime.observations(),
                runtime.server());
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
     * 装配工厂生命周期服务。
     *
     * @param runtime 运行时
     * @return 服务实例
     */
    private static FactoryLifecycleService lifecycle(FactoryRuntime runtime) {
        return new FactoryLifecycleService(runtime.manager(), runtime::markDirty,
                runtime.nodeManager(), machineLinks(runtime), runtime.storageResolver(),
                runtime.observations(), runtime::takeNextSeq);
    }


    // ------------------------------------------------------------------
    // ---- 仓库相关入口：装配服务实例并转发 ----
    // 业务实现见 StorageLinkService / StorageMaintenanceService / StorageConsoleService。
    // ------------------------------------------------------------------

    /**
     * 清空当前仓库地址，保留模式。
     *
     * @param player 发起请求的玩家
     * @return 清空为 {@code 1}
     */
    public static int clearStorage(ServerPlayer player) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return notStarted(player);
        }
        return storageLinks(runtime).clearStorage(player);
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
     * 请求清空仓库：先发聊天确认，点击确认后才真正清空。
     *
     * @param player 发起请求的玩家
     * @return 发送确认消息为 {@code 1}
     */
    public static int requestClearStorage(ServerPlayer player) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return notStarted(player);
        }
        return storageMaintenance(runtime).requestClearStorage(player);
    }

    /**
     * 聊天栏确认/取消清空仓库。
     *
     * @param player  点击的玩家
     * @param token   请求 token
     * @param confirm {@code true} 清空，{@code false} 取消
     * @return 成功为 {@code 1}
     */
    public static int confirmClearStorage(ServerPlayer player, String token, boolean confirm) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return notStarted(player);
        }
        return storageMaintenance(runtime).confirmClearStorage(player, token, confirm);
    }

    /**
     * 把准星选中的方块容器加入工厂仓库。
     *
     * @param player 发起请求的玩家
     * @return 命令结果
     */
    public static int bindContainer(ServerPlayer player) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return notStarted(player);
        }
        return storageMaintenance(runtime).bindContainer(player);
    }

    /**
     * 解绑整个仓库。
     *
     * @param player 发起请求的玩家
     * @return 命令结果
     */
    public static int unbindStorage(ServerPlayer player) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return notStarted(player);
        }
        return storageMaintenance(runtime).unbindStorage(player);
    }

    /**
     * 从容器仓库里移除一个容器。
     *
     * @param player  发起请求的玩家
     * @param shortId 容器短 id
     * @return 命令结果
     */
    public static int unbindContainer(ServerPlayer player, String shortId) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return notStarted(player);
        }
        return storageMaintenance(runtime).unbindContainer(player, shortId);
    }

    /**
     * 设置容器优先级。高的先填、低的先抽。
     *
     * @param player   发起请求的玩家
     * @param shortId  容器短 id
     * @param priority 新优先级
     * @return 命令结果
     */
    public static int setContainerPriority(ServerPlayer player, String shortId, int priority) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return notStarted(player);
        }
        return storageMaintenance(runtime).setContainerPriority(player, shortId, priority);
    }

    /**
     * 清理容器仓库里「已缺失」的容器（被拆、维度没了）。
     *
     * @param player 发起请求的玩家
     * @return 命令结果
     */
    public static int pruneStorage(ServerPlayer player) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return notStarted(player);
        }
        return storageMaintenance(runtime).pruneStorage(player);
    }

    /**
     * 仓库摘要：类型、容器列表、物品。
     *
     * @param player 发起请求的玩家
     * @return 命令结果
     */
    public static int storageInfo(ServerPlayer player) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return notStarted(player);
        }
        return storageConsole(runtime).storageInfo(player);
    }

    /**
     * 从工厂仓库取物品给玩家，类似 {@code /give}。
     *
     * @param player 目标玩家
     * @param sample 物品样本（数量会被忽略）
     * @param count  想取几个
     * @return 命令结果（{@code 1} 取到，{@code 0} 没取到）
     */
    public static int storageGet(ServerPlayer player, ItemStack sample, int count) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return notStarted(player);
        }
        return storageConsole(runtime).storageGet(player, sample, count);
    }

    /**
     * 把玩家背包里的物品放进工厂仓库。
     *
     * @param player 目标玩家
     * @param sample 物品样本（数量会被忽略）
     * @param count  想放几个
     * @return 命令结果（{@code 1} 存了，{@code 0} 没存）
     */
    public static int storagePut(ServerPlayer player, ItemStack sample, int count) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return notStarted(player);
        }
        return storageConsole(runtime).storagePut(player, sample, count);
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

    /**
     * 装配仓库维护服务。
     *
     * @param runtime 运行时
     * @return 服务实例
     */
    private static StorageMaintenanceService storageMaintenance(FactoryRuntime runtime) {
        return new StorageMaintenanceService(runtime.manager(), runtime::markDirty,
                runtime.storageResolver(), runtime.observations(), storageLinks(runtime));
    }

    /**
     * 装配仓库摘要与存取服务。
     *
     * @param runtime 运行时
     * @return 服务实例
     */
    private static StorageConsoleService storageConsole(FactoryRuntime runtime) {
        return new StorageConsoleService(runtime.manager(), runtime.storageResolver(),
                runtime.observations());
    }
}
