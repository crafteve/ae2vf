package io.github.crafteve.idlefactory.factory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.machine.MachineLibrary;
import io.github.crafteve.idlefactory.factory.machine.MachineRef;
import io.github.crafteve.idlefactory.factory.probe.BlockProbe;
import io.github.crafteve.idlefactory.factory.probe.Health;
import io.github.crafteve.idlefactory.factory.observation.Observation;
import io.github.crafteve.idlefactory.factory.location.BlockKey;
import io.github.crafteve.idlefactory.factory.observation.ObservationService;
import io.github.crafteve.idlefactory.factory.links.LinkRegistry;
import io.github.crafteve.idlefactory.factory.links.LinkRole;
import appeng.api.stacks.AEKey;
import io.github.crafteve.idlefactory.factory.storage.ContainerAnchor;
import io.github.crafteve.idlefactory.factory.storage.ContainerRef;
import io.github.crafteve.idlefactory.factory.storage.MeAnchor;
import appeng.api.storage.MEStorage;
import io.github.crafteve.idlefactory.factory.storage.StorageAnchor;
import io.github.crafteve.idlefactory.factory.storage.StorageResolver;
import io.github.crafteve.idlefactory.factory.storage.StorageMode;
import io.github.crafteve.idlefactory.integration.ae2.Ae2Binder;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.capabilities.Capabilities;
import io.github.crafteve.idlefactory.factory.storage.AeKeySupport;

/**
 * 工厂行政窗口：创建 / 删除 / 绑定仓库 / 机器库 / 仓库调试。
 *
 * <p>终端、指令、以后的 WebUI 都走这里。它负责权限（第一版只看 owner）和档案写入，
 * 不负责界面，也不负责搬运。</p>
 */
public final class FactoryService {
    /** {@code storage info} 最多列出多少种物品。 */
    private static final int INFO_LIMIT = 30;
    /** 清空仓库确认的有效期：1200 tick = 60 秒。 */
    private static final long CLEAR_CONFIRM_TICKS = 1200L;
    /** 玩家 → 待确认的清空请求。 */
    private static final Map<UUID, PendingClear> PENDING_CLEARS = new HashMap<>();
    /** 准星操作的射线距离（格）。 */
    private static final double REACH = 10.0D;

    private FactoryService() {
        // 纯静态工具类
    }

    // ------------------------------------------------------------------
    // ---- 静态门面：装配服务实例并转发 ----
    // 保留原有静态签名，调用方零改动；业务实现见 FactoryLifecycleService / MachineLinkService / MachineToggleService。
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
    public static MachineActions.Result deleteMachineById(Factory factory, UUID machineId) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return MachineActions.Result.err("Idle Factory 尚未启动（服务端未就绪）");
        }
        return machineLinks(runtime).deleteMachineById(factory, machineId);
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
     * 装配机器接线服务。
     *
     * @param runtime 运行时
     * @return 服务实例
     */
    private static MachineLinkService machineLinks(FactoryRuntime runtime) {
        return new MachineLinkService(runtime.manager(), runtime::markDirty, runtime.nodeManager(),
                runtime.machineRunner(), runtime.observations(), runtime.server());
    }

    /**
     * 装配机器整组 toggle 服务。
     *
     * @param runtime 运行时
     * @return 服务实例
     */
    private static MachineToggleService machineToggle(FactoryRuntime runtime) {
        return new MachineToggleService(runtime.manager(), runtime::markDirty,
                runtime.observations(), machineLinks(runtime));
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
    // ---- 04. 占用门禁（LinkGate / GateFilter） ----
    // 负责绑定前的全服占用检查与拒绝文案；被机器 toggle、仓库 toggle 与容器连锁三条路径复用。
    // ------------------------------------------------------------------

    /**
     * 门禁过滤结果：放行的目标，以及被别家占、角色冲突而跳过的数量。
     *
     * @param open         放行的目标坐标
     * @param blockedOther 被别的工厂占了的数量
     * @param blockedRole  位置属于自己、但角色不是本次操作角色的数量
     */
    private record GateFilter(List<BlockPos> open, int blockedOther, int blockedRole) {
    }

    /**
     * 把一条认领翻译成人话：谁家的、机器还是仓库。
     *
     * @param claim 认领
     * @return 如“工厂 ab12cd34 的机器”
     */
    private static String describeClaim(LinkRegistry.Claim claim) {
        return "工厂 " + claim.factoryId().toString().substring(0, 8) + " 的" + roleName(claim.role());
    }

    /**
     * 角色名。
     *
     * @param role 角色
     * @return “机器”或“仓库”
     */
    private static String roleName(LinkRole role) {
        return role == LinkRole.MACHINE ? "机器" : "仓库";
    }

    /**
     * 把一次占用检查翻译成拒绝理由；不该拒绝时返回 {@code null}。
     *
     * @param check 检查结果
     * @return 拒绝文案；放行时为 {@code null}
     */
    @Nullable
    private static String gateMessage(LinkGate.ClaimCheck check) {
        LinkRegistry.Claim claim = check.claim();
        if (claim == null) {
            return null;
        }
        if (check.kind() == LinkGate.ClaimCheck.Kind.SELF_OTHER) {
            return "这个位置已经是你的" + roleName(claim.role()) + "，先解除再操作";
        }
        return "该位置已被" + describeClaim(claim) + "占用";
    }

    /**
     * 按占用表过滤一批目标：空地和自己的同角色目标放行，别人的和别人角色的分别计数。
     *
     * <p>连锁是“先按世界事实找全，再按归属过滤”——BFS 的半径与数量上限都在
     * {@code FactoryLinkFinder} 里按筛选前计算，这里只分流，过滤后不补位、不重扫。</p>
     *
     * @param gate    本次操作的占用表
     * @param factory 当前工厂
     * @param role    本次操作的角色
     * @param level   所在维度
     * @param targets 候选坐标
     * @return 过滤结果
     */
    private static GateFilter filterClaimed(LinkGate gate, Factory factory, LinkRole role,
            ServerLevel level, List<BlockPos> targets) {
        List<BlockPos> open = new ArrayList<>(targets.size());
        int blockedOther = 0;
        int blockedRole = 0;
        for (BlockPos targetPos : targets) {
            LinkGate.ClaimCheck check =
                    gate.check(BlockKey.of(level.dimension(), targetPos), factory.id(), role);
            switch (check.kind()) {
                case FREE, SELF_SAME -> open.add(targetPos);
                case SELF_OTHER -> blockedRole++;
                case OTHER -> blockedOther++;
            }
        }
        return new GateFilter(open, blockedOther, blockedRole);
    }

    // ------------------------------------------------------------------
    // ---- 06. 清空与切换仓库（clearStorage / switchStorageMode） ----
    // 负责清空仓库地址与 ME / 容器模式切换；clearStorage 由确认流程调用，switchStorageMode 由绑定杖调用。
    // ------------------------------------------------------------------

    /**
     * 清空当前仓库地址，保留模式。
     *
     * @param player 发起请求的玩家
     * @return 清空为 {@code 1}
     */
    public static int clearStorage(ServerPlayer player) {
        Factory factory = ownedFactory(player);
        if (factory == null) {
            return 0;
        }
        if (factory.config().storageAnchor() == null) {
            barErr(player, "当前没有仓库链接");
            return 0;
        }
        factory.bindStorage(null, storageResolver());
        markDirty();
        barOk(player, "已清空仓库链接（当前 " + modeLabel(factory.config().storageMode()) + " 模式）");
        return 1;
    }

    /**
     * 切换 ME / 容器模式；有链接时拒绝。
     *
     * @param player 发起请求的玩家
     * @return 成功为 {@code 1}
     */
    public static int switchStorageMode(ServerPlayer player) {
        Factory factory = ownedFactory(player);
        if (factory == null) {
            return 0;
        }
        if (factory.config().storageAnchor() != null) {
            barErr(player, "先清空当前链接再切换模式");
            return 0;
        }
        StorageMode next = factory.config().storageMode() == StorageMode.ME
                ? StorageMode.CONTAINER : StorageMode.ME;
        factory.setStorageMode(next);
        markDirty();
        barOk(player, "已切换为 " + modeLabel(next) + " 模式");
        return 1;
    }


    // ------------------------------------------------------------------
    // ---- 08. 绑定杖：仓库 toggle（toggleStorage / toggleMeStorage / toggleContainerStorage） ----
    // 负责绑定杖右键的仓库开关，按 ME 与容器模式分派；由 FactoryWandActionC2S 的 STORAGE_TOGGLE 调用。
    // ------------------------------------------------------------------

    /**
     * 仓库 toggle：ME 或容器按当前模式处理；容器连锁按整组 toggle。
     *
     * @param player 发起请求的玩家
     * @param pos    目标方块
     * @param chain  是否连锁
     * @return 变更数量
     */
    public static int toggleStorage(ServerPlayer player, BlockPos pos, boolean chain) {
        Factory factory = ownedFactory(player);
        if (factory == null) {
            return 0;
        }
        StorageAnchor anchor = factory.config().storageAnchor();
        ServerLevel level = player.serverLevel();
        if (factory.config().storageMode() == StorageMode.ME) {
            return toggleMeStorage(player, factory, level, anchor, pos);
        }
        return toggleContainerStorage(player, factory, level, anchor, pos, chain);
    }

    private static int toggleMeStorage(ServerPlayer player, Factory factory, ServerLevel level,
            StorageAnchor anchor, BlockPos pos) {
        // 先查占用：释放只允许「自己的 + 仓库」角色；别的工厂、自己的机器都拒绝。
        LinkGate gate = LinkGate.of(FactoryRuntime.get().manager());
        LinkGate.ClaimCheck claim =
                gate.check(BlockKey.of(level.dimension(), pos), factory.id(), LinkRole.STORAGE);
        if (claim.blocked()) {
            barErr(player, gateMessage(claim));
            return 0;
        }
        boolean same = anchor instanceof MeAnchor me
                && me.dimension().equals(level.dimension()) && me.pos().equals(pos);
        if (same) {
            factory.bindStorage(null, storageResolver());
            markDirty();
            barOk(player, "已取消 ME 仓库链接");
            return 1;
        }
        if (anchor != null) {
            barErr(player, "已绑定其他仓库，先右键空气清空");
            return 0;
        }
        if (!level.isLoaded(pos)) {
            barErr(player, "目标区块未加载");
            return 0;
        }
        if (Ae2Binder.nodeAt(level, pos) == null) {
            barErr(player, "该方块没有接入 ME 网络");
            return 0;
        }
        factory.bindStorage(new MeAnchor(level.dimension(), pos), storageResolver());
        markDirty();
        barOk(player, "已绑定 ME 仓库 " + blockLabel(level, pos));
        return 1;
    }

    private static int toggleContainerStorage(ServerPlayer player, Factory factory,
            ServerLevel level, StorageAnchor anchor, BlockPos pos, boolean chain) {
        if (anchor instanceof MeAnchor) {
            barErr(player, "当前是 ME 模式，先清空当前链接");
            return 0;
        }
        ContainerAnchor current = anchor instanceof ContainerAnchor containerAnchor
                ? containerAnchor : new ContainerAnchor(List.of());
        List<BlockPos> found = chain
                ? FactoryLinkFinder.containers(level, pos)
                : List.of(pos);
        if (found.isEmpty()) {
            found = List.of(pos);
        }
        LinkGate gate = LinkGate.of(FactoryRuntime.get().manager());
        LinkGate.ClaimCheck origin =
                gate.check(BlockKey.of(level.dimension(), pos), factory.id(), LinkRole.STORAGE);
        if (origin.blocked()) {
            barErr(player, gateMessage(origin));
            return 0;
        }
        // BFS 的半径与数量上限按筛选前计算；这里只按占用表分流，过滤后不补位、不重扫。
        GateFilter filter = filterClaimed(gate, factory, LinkRole.STORAGE, level, found);
        List<BlockPos> targets = filter.open();
        if (targets.isEmpty()) {
            barErr(player, "这些位置都不能绑定");
            return 0;
        }
        boolean allBound = true;
        for (BlockPos targetPos : targets) {
            if (!current.contains(ContainerRef.of(level.dimension(), targetPos).id())) {
                allBound = false;
                break;
            }
        }
        if (allBound) {
            return unbindContainerTargets(player, factory, level, current, targets, chain);
        }
        return bindContainerTargets(player, factory, level, current, targets, chain,
                filter.blockedOther(), filter.blockedRole());
    }

    private static int bindContainerTargets(ServerPlayer player, Factory factory,
            ServerLevel level, ContainerAnchor current, List<BlockPos> targets, boolean chain,
            int blockedOther, int blockedRole) {
        if (!level.isLoaded(targets.getFirst())) {
            barErr(player, "目标区块未加载");
            return 0;
        }
        BlockPos origin = targets.getFirst();
        if (Ae2Binder.nodeAt(level, origin) != null) {
            barErr(player, "这是 ME 网络方块，请切到 ME 模式");
            return 0;
        }
        if (!isAnyContainer(level, origin)) {
            barErr(player, "该方块不是仓库容器");
            return 0;
        }
        ContainerAnchor next = current;
        int added = 0;
        int skipped = 0;
        for (BlockPos targetPos : targets) {
            if (Ae2Binder.nodeAt(level, targetPos) != null) {
                skipped++;
                continue;
            }
            ContainerRef ref = ContainerRef.of(level.dimension(), targetPos);
            if (current.contains(ref.id())) {
                skipped++;
                continue;
            }
            if (next.containers().size() >= ContainerAnchor.MAX_CONTAINERS) {
                skipped++;
                continue;
            }
            next = next.with(ref);
            added++;
        }
        if (added == 0) {
            if (blockedOther > 0) {
                barErr(player, "这些容器都被别的工厂占了");
            } else if (blockedRole > 0) {
                barErr(player, "这些位置已经是你的机器，先解除");
            } else {
                barErr(player, "没有新增容器（已绑过或达到上限 "
                        + ContainerAnchor.MAX_CONTAINERS + " 个）");
            }
            return 0;
        }
        factory.bindStorage(next, storageResolver());
        markDirty();
        if (!chain) {
            barOk(player, "已绑定容器 " + blockLabel(level, origin));
            return added;
        }
        String text = "已绑定 " + added + " 个容器，共 " + next.containers().size() + " 个";
        if (skipped > 0) {
            text += "，跳过 " + skipped + " 个";
        }
        if (blockedOther > 0) {
            text += "，被别家占用 " + blockedOther + " 个";
        }
        if (blockedRole > 0) {
            text += "，角色冲突 " + blockedRole + " 个";
        }
        barOk(player, text);
        return added;
    }

    private static int unbindContainerTargets(ServerPlayer player, Factory factory,
            ServerLevel level, ContainerAnchor current, List<BlockPos> targets, boolean chain) {
        ContainerAnchor next = current;
        int removed = 0;
        for (BlockPos targetPos : targets) {
            ContainerRef ref = ContainerRef.of(level.dimension(), targetPos);
            if (current.contains(ref.id())) {
                next = next.without(ref.id());
                removed++;
            }
        }
        if (removed == 0) {
            barErr(player, "该位置没有绑定容器");
            return 0;
        }
        factory.bindStorage(next.isEmpty() ? null : next, storageResolver());
        factory.setStorageMode(StorageMode.CONTAINER);
        markDirty();
        if (!chain) {
            String label = blockLabel(level, targets.getFirst());
            barOk(player, next.isEmpty()
                    ? "已取消容器链接 " + label + "，仓库已清空"
                    : "已取消容器链接 " + label);
            return removed;
        }
        barOk(player, "已取消 " + removed + " 个容器"
                + (next.isEmpty() ? "，仓库已清空" : "，剩余 " + next.containers().size() + " 个"));
        return removed;
    }

    // ------------------------------------------------------------------
    // ---- 09. 清空确认与标签工具（requestClearStorage / confirmClearStorage / PendingClear） ----
    // 负责清空仓库的两步确认与聊天栏点击指令，并提供机器、方块与模式的显示名；由绑定杖与 /idf storage clear 调用。
    // ------------------------------------------------------------------

    /**
     * 请求清空仓库：先发聊天确认，点击确认后才真正清空。
     *
     * @param player 发起请求的玩家
     * @return 发送确认消息为 {@code 1}
     */
    public static int requestClearStorage(ServerPlayer player) {
        Factory factory = ownedFactory(player);
        if (factory == null) {
            return 0;
        }
        if (factory.config().storageAnchor() == null) {
            barErr(player, "当前没有仓库链接");
            return 0;
        }
        UUID token = UUID.randomUUID();
        long expiresAt = player.serverLevel().getGameTime() + CLEAR_CONFIRM_TICKS;
        PENDING_CLEARS.put(player.getUUID(), new PendingClear(token, expiresAt));
        Component confirm = Component.literal("[✓] 确认").withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                        "/idf storage clear confirm " + token))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal("清空当前仓库链接"))));
        Component cancel = Component.literal("[×] 取消").withStyle(style -> style
                .withColor(ChatFormatting.RED)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                        "/idf storage clear cancel " + token))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal("保留当前仓库链接"))));
        player.sendSystemMessage(Component.literal("[!!] 清空仓库链接？ ").withStyle(ChatFormatting.RED)
                .append(confirm)
                .append(Component.literal("  "))
                .append(cancel));
        barOk(player, "确认消息已发到聊天栏");
        return 1;
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
        PendingClear pending = PENDING_CLEARS.get(player.getUUID());
        if (pending == null || !pending.token().toString().equals(token)) {
            barErr(player, "确认已失效，请重新发起");
            return 0;
        }
        if (player.serverLevel().getGameTime() > pending.expiresAt()) {
            PENDING_CLEARS.remove(player.getUUID());
            barErr(player, "确认已过期，请重新发起");
            return 0;
        }
        PENDING_CLEARS.remove(player.getUUID());
        if (!confirm) {
            barOk(player, "已取消清空仓库");
            return 1;
        }
        return clearStorage(player);
    }

    private static String machineLabel(ServerLevel level, BlockPos pos) {
        return blockLabel(level, pos);
    }

    private static String blockLabel(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return pos.toShortString();
        }
        return pos.toShortString() + "（"
                + level.getBlockState(pos).getBlock().getName().getString() + "）";
    }

    private record PendingClear(UUID token, long expiresAt) {
    }

    private static String modeLabel(StorageMode mode) {
        return mode == StorageMode.ME ? "ME" : "容器";
    }

    // ------------------------------------------------------------------
    // ---- 11. 容器仓库维护（bindContainer / unbindStorage / setContainerPriority / pruneStorage） ----
    // 负责容器仓库的增删、优先级与已缺失清理；由 /idf storage 的 bind、unbind、priority 与 prune 调用。
    // ------------------------------------------------------------------

    /**
     * 把准星选中的方块容器加入工厂仓库。
     *
     * <p>主路径是 NeoForge 的方块能力（原版箱子、木桶、熔炉都已注册），{@link Container}
     * 包装只作兜底。已有 AE2 仓库时拒绝——当前阶段两者互斥。</p>
     *
     * @param player 发起请求的玩家
     * @return 命令结果
     */
    public static int bindContainer(ServerPlayer player) {
        Factory factory = ownedFactory(player);
        if (factory == null) {
            return 0;
        }
        BlockPos pos = pickedBlock(player);
        if (pos == null) {
            err(player, "没有对准方块（射线 " + (int) REACH + " 格）");
            return 0;
        }
        ServerLevel level = player.serverLevel();
        if (!level.isLoaded(pos)) {
            err(player, "这个位置区块未加载");
            return 0;
        }
        if (!isAnyContainer(level, pos)) {
            err(player, "这个方块不是仓库容器（物品 / 流体 / 能量）");
            return 0;
        }
        // 指令路径也是绑定入口，必须和绑定杖走同一套占用规则；自占同角色交给下面的
        // contains 判断。
        LinkGate gate = LinkGate.of(FactoryRuntime.get().manager());
        LinkGate.ClaimCheck claim =
                gate.check(BlockKey.of(level.dimension(), pos), factory.id(), LinkRole.STORAGE);
        if (claim.blocked()) {
            err(player, gateMessage(claim));
            return 0;
        }

        ContainerRef ref = ContainerRef.of(level.dimension(), pos);
        StorageAnchor current = factory.config().storageAnchor();
        ContainerAnchor anchor;
        if (current == null) {
            anchor = new ContainerAnchor(List.of(ref));
        } else if (current instanceof ContainerAnchor containerAnchor) {
            if (containerAnchor.contains(ref.id())) {
                err(player, "这个容器已经绑过了");
                return 0;
            }
            if (containerAnchor.containers().size() >= ContainerAnchor.MAX_CONTAINERS) {
                err(player, "一个仓库最多 " + ContainerAnchor.MAX_CONTAINERS + " 个容器");
                return 0;
            }
            anchor = containerAnchor.with(ref);
        } else {
            err(player, "当前已绑定 AE2 仓库，先执行 /idf storage unbind 再绑容器");
            return 0;
        }

        factory.bindStorage(anchor, storageResolver());
        markDirty();
        ok(player, "已加入仓库容器 [" + ref.shortId() + "] " + level.dimension().location()
                + " " + pos.toShortString() + "（共 " + anchor.containers().size() + " 个）");
        return 1;
    }

    /**
     * 解绑整个仓库。
     *
     * @param player 发起请求的玩家
     * @return 命令结果
     */
    public static int unbindStorage(ServerPlayer player) {
        Factory factory = ownedFactory(player);
        if (factory == null) {
            return 0;
        }
        if (factory.config().storageAnchor() == null) {
            err(player, "还没有绑定仓库");
            return 0;
        }
        factory.bindStorage(null, storageResolver());
        markDirty();
        ok(player, "已解绑仓库");
        return 1;
    }

    /**
     * 从容器仓库里移除一个容器。
     *
     * @param player  发起请求的玩家
     * @param shortId 容器短 id
     * @return 命令结果
     */
    public static int unbindContainer(ServerPlayer player, String shortId) {
        Factory factory = ownedFactory(player);
        if (factory == null) {
            return 0;
        }
        if (!(factory.config().storageAnchor() instanceof ContainerAnchor anchor)) {
            err(player, "当前没有容器仓库");
            return 0;
        }
        Optional<ContainerRef> ref = anchor.byShortId(shortId);
        if (ref.isEmpty()) {
            err(player, "找不到容器 " + shortId);
            return 0;
        }
        ContainerAnchor next = anchor.without(ref.get().id());
        factory.bindStorage(next.isEmpty() ? null : next, storageResolver());
        markDirty();
        ok(player, "已移除容器 [" + ref.get().shortId() + "]");
        return 1;
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
        Factory factory = ownedFactory(player);
        if (factory == null) {
            return 0;
        }
        if (!(factory.config().storageAnchor() instanceof ContainerAnchor anchor)) {
            err(player, "当前没有容器仓库");
            return 0;
        }
        Optional<ContainerRef> ref = anchor.byShortId(shortId);
        if (ref.isEmpty()) {
            err(player, "找不到容器 " + shortId);
            return 0;
        }
        factory.bindStorage(anchor.withPriority(ref.get().id(), priority), storageResolver());
        markDirty();
        ok(player, "容器 [" + ref.get().shortId() + "] 优先级 → " + priority);
        return 1;
    }

    /**
     * 清理容器仓库里「已缺失」的容器（被拆、维度没了）。
     *
     * <p>只删<b>确认不在</b>的；未加载的一律保留——那是「不知道」，不是「没了」。</p>
     *
     * @param player 发起请求的玩家
     * @return 命令结果
     */
    public static int pruneStorage(ServerPlayer player) {
        Factory factory = ownedFactory(player);
        if (factory == null) {
            return 0;
        }
        if (!(factory.config().storageAnchor() instanceof ContainerAnchor anchor)) {
            err(player, "当前没有容器仓库（AE2 仓库不需要清理）");
            return 0;
        }
        ObservationService observations = observations();
        if (observations == null) {
            err(player, "观测服务未就绪");
            return 0;
        }
        List<ContainerRef> keep = new ArrayList<>(anchor.containers().size());
        int removed = 0;
        for (ContainerRef ref : anchor.containers()) {
            if (observations.observe(ref.blockKey()).health().gone()) {
                removed++;
            } else {
                keep.add(ref);
            }
        }
        if (removed == 0) {
            info(player, "没有已缺失的容器（未加载的不算）");
            return 1;
        }
        factory.bindStorage(keep.isEmpty() ? null : new ContainerAnchor(keep), storageResolver());
        markDirty();
        ok(player, "已清理 " + removed + " 个已缺失的容器"
                + (keep.isEmpty() ? " · 仓库已解绑" : " · 剩 " + keep.size() + " 个"));
        return 1;
    }

    // ------------------------------------------------------------------
    // ---- 12. 仓库判定与展示辅助（containerState / isAnyContainer） ----
    // 负责容器状态与「是不是容器」的判定；被仓库摘要、仓库绑定与连锁查找共用。
    // ------------------------------------------------------------------

    /**
     * 容器状态的一句话。
     *
     * @param health 观测结论
     * @return 展示文本
     */
    private static String containerState(Health health) {
        return switch (health) {
            case PRESENT -> "可用";
            case UNLOADED -> "未加载";
            case MISSING, NO_DIMENSION -> "已缺失";
            case UNKNOWN -> "未探测";
        };
    }

    static boolean isAnyContainer(ServerLevel level, BlockPos pos) {
        if (level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null) != null) {
            return true;
        }
        if (level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null) != null) {
            return true;
        }
        if (level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, null) != null) {
            return true;
        }
        return level.getBlockEntity(pos) instanceof Container;
    }

    // ------------------------------------------------------------------
    // ---- 13. 仓库摘要与存取（storageInfo / storageGet / storagePut） ----
    // 负责仓库内容摘要、取出与存入；由 /idf storage 的 info、get 与 put 调用。
    // ------------------------------------------------------------------

    /**
     * 仓库摘要：类型、容器列表、物品。
     *
     * @param player 发起请求的玩家
     * @return 命令结果
     */
    public static int storageInfo(ServerPlayer player) {
        Factory factory = ownedFactory(player);
        if (factory == null) {
            return 0;
        }
        factory.refreshStorage(storageResolver());
        StorageAnchor anchor = factory.config().storageAnchor();
        if (anchor == null) {
            err(player, "还没有绑定仓库");
            info(player, "容器：准星对准箱子执行 /idf storage bind；ME：用存储链接杖右键 ME 网络方块");
            return 0;
        }
        info(player, "仓库：" + anchor.describe() + " · "
                + (factory.live().storageOnline() ? "在线" : "离线"));

        ObservationService observations = observations();
        if (anchor instanceof ContainerAnchor containerAnchor) {
            int present = 0;
            int unloaded = 0;
            int missing = 0;
            for (ContainerRef ref : containerAnchor.containers()) {
                Health health = observations == null ? Health.UNKNOWN : observations.observe(ref.blockKey()).health();
                switch (health) {
                    case PRESENT -> present++;
                    case UNLOADED -> unloaded++;
                    case MISSING, NO_DIMENSION -> missing++;
                    default -> {
                        // UNKNOWN 不计
                    }
                }
                Component row = Component.empty()
                        .append(FactoryMessages.text("  ", FactoryMessages.INFO))
                        .append(FactoryMessages.hover(ref.shortId(),
                                ref.dimension().location() + " " + ref.pos().toShortString()))
                        .append(FactoryMessages.text(" " + ref.dimension().location() + " "
                                + ref.pos().toShortString()
                                + " · " + containerState(health)
                                + " · 优先级 " + ref.priority(), FactoryMessages.INFO));
                FactoryMessages.send(player, row);
            }
            info(player, "容器 " + containerAnchor.containers().size() + " 个（可用 " + present
                    + (unloaded > 0 ? " · 未加载 " + unloaded : "")
                    + (missing > 0 ? " · 已缺失 " + missing : "") + "）"
                    + (missing > 0 ? " · /idf storage prune 清理已缺失" : ""));
        }

        if (!factory.live().storageOnline()) {
            warn(player, "仓库当前离线，机器不会搬运");
            return 1;
        }

        MEStorage storage = factory.live().storage();
        Map<AEKey, Long> snapshot = AeKeySupport.snapshot(storage);
        if (snapshot.isEmpty()) {
            info(player, "仓库是空的");
            return 1;
        }
        List<Map.Entry<AEKey, Long>> entries = new ArrayList<>(snapshot.entrySet());
        entries.sort(Map.Entry.<AEKey, Long>comparingByValue().reversed());
        int shown = Math.min(entries.size(), INFO_LIMIT);
        for (int i = 0; i < shown; i++) {
            Map.Entry<AEKey, Long> entry = entries.get(i);
            msg(player, "  " + describeResource(entry.getKey()) + " x " + entry.getValue());
        }
        if (entries.size() > shown) {
            msg(player, "  ... 还有 " + (entries.size() - shown) + " 种");
        }
        info(player, "共 " + entries.size() + " 种资源");
        return 1;
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
        Factory factory = ownedFactory(player);
        if (factory == null) {
            return 0;
        }
        MEStorage storage = requireOnlineStorage(player, factory);
        if (storage == null) {
            return 0;
        }
        long extracted = AeKeySupport.extract(storage, AeKeySupport.item(sample), count, false);
        if (extracted <= 0) {
            err(player, "仓库里没有 " + itemId(sample));
            return 0;
        }
        ItemStack give = sample.copy();
        give.setCount((int) extracted);
        if (!player.getInventory().add(give)) {
            // 背包放不下就掉地上，和 /give 一致
            player.drop(give, false);
        }
        ok(player, "取出 " + extracted + " 个 " + itemId(sample));
        return 1;
    }

    /**
     * 把玩家背包里的物品放进工厂仓库。
     *
     * <p>生存模式先统计背包、再按仓库点头量扣背包；创造模式不统计背包（默认背包无限），
     * 只按仓库能收多少来放。</p>
     *
     * @param player 目标玩家
     * @param sample 物品样本（数量会被忽略）
     * @param count  想放几个
     * @return 命令结果（{@code 1} 存了，{@code 0} 没存）
     */
    public static int storagePut(ServerPlayer player, ItemStack sample, int count) {
        Factory factory = ownedFactory(player);
        if (factory == null) {
            return 0;
        }
        MEStorage storage = requireOnlineStorage(player, factory);
        if (storage == null) {
            return 0;
        }
        ItemStack template = sample.copy();
        template.setCount(1);

        if (player.isCreative()) {
            long inserted = AeKeySupport.insert(storage, AeKeySupport.item(template), count, false);
            if (inserted <= 0) {
                err(player, "仓库收不下 " + itemId(sample));
                return 0;
            }
            ok(player, "存入 " + inserted + " 个 " + itemId(sample) + "（创造模式）");
            return 1;
        }

        int available = countInInventory(player, template);
        if (available <= 0) {
            err(player, "背包里没有 " + itemId(sample));
            return 0;
        }
        int want = Math.min(count, available);
        long accepted = AeKeySupport.insert(storage, AeKeySupport.item(template), want, true);
        if (accepted <= 0) {
            err(player, "仓库收不下 " + itemId(sample));
            return 0;
        }
        long inserted = AeKeySupport.insert(storage, AeKeySupport.item(template), accepted, false);
        int removed = removeFromInventory(player, template, (int) inserted);
        ok(player, "存入 " + removed + " 个 " + itemId(sample));
        return removed > 0 ? 1 : 0;
    }

    // ------------------------------------------------------------------
    // ---- 14. 内部工具：工厂与仓库解析（ownedFactory / requireOnlineStorage） ----
    // 负责取当前工厂与解析在线仓库；本文件所有公开方法共用。
    // ------------------------------------------------------------------

    /**
     * 取玩家自己的工厂；没有就提示。
     *
     * @param player 玩家
     * @return 工厂；运行时未就绪或玩家没有工厂时为 {@code null}
     */
    @Nullable
    private static Factory ownedFactory(ServerPlayer player) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            err(player, "Idle Factory 尚未启动（服务端未就绪）");
            return null;
        }
        Optional<Factory> factory = runtime.manager().byOwner(player.getUUID());
        if (factory.isEmpty()) {
            err(player, "你还没有工厂，先执行 /idlefactory create");
            return null;
        }
        return factory.get();
    }

    /**
     * 取一个当前确实可用的仓库；没绑地址、没电、环离线都给出明确提示。
     *
     * <p>指令是即时的，这里先刷新一次，不等 {@code FactoryRuntime} 的定时刷新。</p>
     *
     * @param player  玩家
     * @param factory 工厂
     * @return 可用仓库；不可用时为 {@code null}
     */
    @Nullable
    private static MEStorage requireOnlineStorage(ServerPlayer player, Factory factory) {
        factory.refreshStorage(storageResolver());
        if (factory.config().storageAnchor() == null) {
            err(player, "你还没有绑定仓库：用存储链接杖右键 ME 网络方块，或对准箱子执行 /idf storage bind");
            return null;
        }
        if (!factory.live().storageOnline()) {
            err(player, "仓库未连接（网络没电、频道不足，或 ME 接入点区块未加载）");
            return null;
        }
        return factory.live().storage();
    }

    /**
     * 玩家准星指向的方块坐标。
     *
     * @param player 玩家
     * @return 方块坐标；没对准方块时为 {@code null}
     */
    @Nullable
    private static BlockPos pickedBlock(ServerPlayer player) {
        HitResult hit = player.pick(REACH, 0.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult blockHit)) {
            return null;
        }
        return blockHit.getBlockPos();
    }

    /**
     * 取观测服务；服务端未就绪时为 {@code null}。
     *
     * @return 观测服务
     */
    @Nullable
    private static ObservationService observations() {
        FactoryRuntime runtime = FactoryRuntime.get();
        return runtime == null ? null : runtime.observations();
    }

    /**
     * 取仓库解析器；运行时未就绪时为 {@code null}。
     *
     * <p>绑定与指令刷新都要立即解析，所以直接从运行时借同一个解析器，不另建。</p>
     *
     * @return 仓库解析器
     */
    @Nullable
    private static StorageResolver storageResolver() {
        FactoryRuntime runtime = FactoryRuntime.get();
        return runtime == null ? null : runtime.storageResolver();
    }


    // ------------------------------------------------------------------
    // ---- 15. 内部工具：背包、文案与回执（countInInventory / msg / markDirty） ----
    // 负责背包装卸、资源与物品文案、聊天与动作栏回执；markDirty 是本文件唯一的档案落盘入口。
    // ------------------------------------------------------------------


    /** 背包里与样本同物品的总数。 */
    private static int countInInventory(ServerPlayer player, ItemStack template) {
        int total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack slot = player.getInventory().getItem(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, template)) {
                total += slot.getCount();
            }
        }
        return total;
    }

    /** 从背包扣掉指定数量，返回实际扣掉的数量。 */
    private static int removeFromInventory(ServerPlayer player, ItemStack template, int amount) {
        int remaining = amount;
        for (int i = 0; i < player.getInventory().getContainerSize() && remaining > 0; i++) {
            ItemStack slot = player.getInventory().getItem(i);
            if (slot.isEmpty() || !ItemStack.isSameItemSameComponents(slot, template)) {
                continue;
            }
            int take = Math.min(slot.getCount(), remaining);
            slot.shrink(take);
            remaining -= take;
        }
        player.getInventory().setChanged();
        return amount - remaining;
    }

    /** 物品注册名。 */
    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** 仓库条目的一行显示，按类型补单位。 */
    private static String describeResource(AEKey key) {
        return switch (AeKeySupport.kind(key)) {
            case FLUID -> key.getDisplayName().getString() + "（mB）";
            case ENERGY -> key.getDisplayName().getString() + "（FE）";
            default -> key.getDisplayName().getString();
        };
    }

    /** 给玩家发一条系统消息。 */
    private static void msg(ServerPlayer player, String text) {
        FactoryMessages.send(player, FactoryMessages.plain(text));
    }

    /** 成功回执。 */
    private static void ok(ServerPlayer player, String text) {
        FactoryMessages.send(player, FactoryMessages.ok(text));
    }

    /** 失败回执。 */
    private static void err(ServerPlayer player, String text) {
        FactoryMessages.send(player, FactoryMessages.err(text));
    }

    /** 说明性输出。 */
    private static void info(ServerPlayer player, String text) {
        FactoryMessages.send(player, FactoryMessages.info(text));
    }

    /** 警告输出。 */
    private static void warn(ServerPlayer player, String text) {
        FactoryMessages.send(player, FactoryMessages.warn(text));
    }

    /** 动作栏成功提示。 */
    private static void barOk(ServerPlayer player, String text) {
        player.displayClientMessage(
                Component.literal("[OK] " + text).withStyle(ChatFormatting.GREEN), true);
    }

    /** 动作栏失败提示。 */
    private static void barErr(ServerPlayer player, String text) {
        player.displayClientMessage(
                Component.literal("[!!] " + text).withStyle(ChatFormatting.RED), true);
    }

    /** 把档案写回存档容器。 */
    private static void markDirty() {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime != null) {
            runtime.markDirty();
        }
    }
}
