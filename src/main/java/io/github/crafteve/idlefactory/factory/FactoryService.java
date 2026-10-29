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
import io.github.crafteve.idlefactory.factory.line.PassiveLine;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.machine.MachineRef;
import io.github.crafteve.idlefactory.factory.probe.BlockProbe;
import io.github.crafteve.idlefactory.factory.probe.Health;
import io.github.crafteve.idlefactory.factory.observation.Observation;
import io.github.crafteve.idlefactory.factory.location.BlockKey;
import io.github.crafteve.idlefactory.factory.observation.ObservationService;
import io.github.crafteve.idlefactory.factory.links.LinkRegistry;
import io.github.crafteve.idlefactory.factory.links.LinkRole;
import io.github.crafteve.idlefactory.factory.resource.ResourceKey;
import io.github.crafteve.idlefactory.factory.storage.ContainerAnchor;
import io.github.crafteve.idlefactory.factory.storage.ContainerRef;
import io.github.crafteve.idlefactory.factory.storage.MeAnchor;
import io.github.crafteve.idlefactory.factory.storage.Storage;
import io.github.crafteve.idlefactory.factory.storage.StorageAnchor;
import io.github.crafteve.idlefactory.factory.storage.StorageResolver;
import io.github.crafteve.idlefactory.factory.storage.StorageMode;
import io.github.crafteve.idlefactory.integration.ae2.Ae2Binder;
import io.github.crafteve.idlefactory.menu.FactoryMenu;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.capabilities.Capabilities;

/**
 * 工厂行政窗口：创建 / 删除 / 绑定仓库 / 机器库 / 仓库调试。
 *
 * <p>终端、指令、以后的 WebUI 都走这里。它负责权限（第一版只看 owner）和档案写入，
 * 不负责界面，也不负责搬运。</p>
 */
public final class FactoryService {
    /** {@code storage info} 最多列出多少种物品。 */
    private static final int INFO_LIMIT = 30;
    /** {@code factory info} 最多列多少台机器。 */
    private static final int INFO_MACHINE_LIMIT = 20;
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
    // ---- 01. 界面入口（openGui / ensureFactory） ----
    // 负责打开终端菜单并保证玩家有工厂；入口是 /idf gui、终端右键与快捷键，ensureFactory 只被 openGui 调用。
    // ------------------------------------------------------------------

    /**
     * 打开工厂工作台。
     *
     * <p>入口有三个：{@code /idf gui}、手持工厂终端右键、终端快捷键。快捷键在客户端先查一次
     * 背包，服务端收到请求后再查一次；三条入口最终都走这里，不另写打开逻辑。</p>
     *
     * <p>第一次打开时自动给玩家建一个工厂，省掉先执行 {@code /idf create} 的空状态。
     * 创建失败只记日志，不挡打开；界面仍会显示未创建。</p>
     *
     * @param player 发起请求的玩家
     * @return 命令结果（{@code 1} 已请求打开）
     */
    public static int openGui(ServerPlayer player) {
        ensureFactory(player);
        player.openMenu(new SimpleMenuProvider(
                (containerId, playerInventory, ignored) -> new FactoryMenu(containerId, playerInventory),
                Component.translatable("screen.idlefactory.title")));
        return 1;
    }

    /**
     * 保证玩家有工厂：没有就静默创建，已有就不动。
     *
     * <p>创建逻辑与 {@link #create(ServerPlayer)} 共用 id 规则，但不发指令回执；自动创建
     * 不应该在第一次打开终端时刷屏。</p>
     *
     * @param player 玩家
     */
    private static void ensureFactory(ServerPlayer player) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return;
        }
        if (runtime.manager().byOwner(player.getUUID()).isPresent()) {
            return;
        }
        UUID id = nextId(runtime, player.getUUID());
        Factory factory = new Factory(id,
                new FactoryConfig(player.getUUID(), StorageMode.ME, null));
        if (!runtime.manager().add(factory)) {
            IdleFactory.LOGGER.warn("自动创建工厂失败：player={}, id={}", player.getUUID(), id);
            return;
        }
        runtime.markDirty();
    }

    // ------------------------------------------------------------------
    // ---- 02. 工厂生命周期（create / delete / info） ----
    // 负责创建、删除与打印工厂摘要；由 /idf create、/idf delete 与 /idf factory info 调用，info 的逐行渲染也在本节。
    // ------------------------------------------------------------------

    /**
     * 给玩家创建一个工厂。一人一个，已有工厂时不动。
     *
     * @param player 发起请求的玩家
     * @return 命令结果（{@code 1} 成功，{@code 0} 未创建）
     */
    public static int create(ServerPlayer player) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            err(player, "Idle Factory 尚未启动（服务端未就绪）");
            return 0;
        }
        Optional<Factory> existing = runtime.manager().byOwner(player.getUUID());
        if (existing.isPresent()) {
            err(player, "你已经有工厂了：" + existing.get().id());
            return 0;
        }

        UUID id = nextId(runtime, player.getUUID());
        Factory factory = new Factory(id,
                new FactoryConfig(player.getUUID(), StorageMode.ME, null));
        if (!runtime.manager().add(factory)) {
            err(player, "工厂创建失败：id 或拥有者冲突");
            return 0;
        }
        runtime.markDirty();
        ok(player, "工厂已创建：" + id);
        return 1;
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
            err(player, "Idle Factory 尚未启动（服务端未就绪）");
            return 0;
        }
        Optional<Factory> factory = runtime.manager().byOwner(player.getUUID());
        if (factory.isEmpty()) {
            err(player, "你还没有工厂，先执行 /idlefactory create");
            return 0;
        }
        runtime.manager().remove(factory.get().id());
        runtime.markDirty();
        ok(player, "工厂已删除：" + factory.get().id());
        return 1;
    }

    /**
     * 打印工厂摘要和机器库。
     *
     * <p>读路径<b>不探世界</b>：每台机器的状态取自上一次的观测结论（{@link ObservationService}）。
     * 刚 connect / 刚恢复的机器会被立刻刷新过，所以第一眼看到的就是准的。</p>
     *
     * @param player 发起请求的玩家
     * @return 命令结果
     */
    public static int info(ServerPlayer player) {
        Factory factory = ownedFactory(player);
        if (factory == null) {
            return 0;
        }
        // 即时刷新一次，别让 online 停在 tick 定时器上一秒的状态
        factory.refreshStorage(storageResolver());
        info(player, FactorySnapshotBuilder.describe(factory));

        ObservationService observations = observations();
        List<FactorySnapshotBuilder.MachineView> machines =
                FactorySnapshotBuilder.machines(factory, observations,
                        player.serverLevel().getGameTime());
        if (machines.isEmpty()) {
            info(player, "机器库为空：准星对准机器执行 /idf factory connect");
            return 1;
        }
        info(player, FactorySnapshotBuilder.health(factory, machines).describe());

        int binCount = 0;
        for (FactorySnapshotBuilder.MachineView view : machines) {
            if (view.binned()) {
                binCount++;
            }
        }
        int shown = 0;
        for (FactorySnapshotBuilder.MachineView view : machines) {
            if (shown >= INFO_MACHINE_LIMIT) {
                info(player, "… 还有 " + (machines.size() - shown) + " 台");
                break;
            }
            sendMachineRow(player, view);
            shown++;
        }
        if (binCount > 0) {
            info(player, "回收站 " + binCount + " 台 · /idf factory bin 查看");
        }
        return 1;
    }

    private static void sendMachineRow(ServerPlayer player, FactorySnapshotBuilder.MachineView view) {
        MachineRef ref = view.ref();
        String where = ref.dimension().location() + " " + ref.pos().toShortString();
        String status = FactorySnapshotBuilder.describe(view);
        StringBuilder hover = new StringBuilder(where).append('\n').append(status);
        if (!view.lineNames().isEmpty()) {
            hover.append("\n产线：").append(String.join(" / ", view.lineNames()))
                    .append(" · 规则 ").append(view.ruleCount()).append(" 条");
        }
        Component row = Component.empty()
                .append(FactoryMessages.text("  ", FactoryMessages.INFO))
                .append(FactoryMessages.hover(ref.shortId(), hover.toString()))
                .append(FactoryMessages.text(" " + where + "  " + status, FactoryMessages.INFO));
        FactoryMessages.send(player, row);
    }

    // ------------------------------------------------------------------
    // ---- 03. 机器库：准星接入与断开（connectMachine / disconnectMachine） ----
    // 负责准星选中机器的接入、断开与回收站分流；由 /idf factory connect 与 /idf factory disconnect 调用。
    // ------------------------------------------------------------------

    /**
     * 把准星选中的机器接入玩家自己的工厂。
     *
     * <p>只接受「方块实体 + 至少提供 {@code IItemHandler} / {@code IFluidHandler}」的方块。
     * id 由维度 + 坐标派生，重复接入会自然去重。</p>
     *
     * @param player 发起请求的玩家
     * @return 命令结果（{@code 1} 成功，{@code 0} 失败）
     */
    public static int connectMachine(ServerPlayer player) {
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
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            err(player, "这个方块没有方块实体");
            return 0;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            err(player, "服务端未就绪");
            return 0;
        }
        // 指令路径也是绑定入口，必须和绑定杖走同一套占用规则；自占同角色交给下面的
        // byId 判断（可能是回收站取回，也可能是数据修复）。
        LinkGate gate = LinkGate.of(FactoryRuntime.get().manager());
        LinkGate.ClaimCheck claim =
                gate.check(BlockKey.of(level.dimension(), pos), factory.id(), LinkRole.MACHINE);
        if (claim.blocked()) {
            err(player, gateMessage(claim));
            return 0;
        }
        MachineRef target = MachineRef.of(level.dimension(), pos);
        BlockProbe.Presence presence = BlockProbe.presence(server,
                BlockKey.of(target.dimension(), target.pos()));
        if (presence.health() != Health.PRESENT || presence.kinds().isEmpty()) {
            err(player, "这个方块没有可搬运的存储（需要物品 / 流体 / 能量能力）");
            return 0;
        }
        Optional<Machine> existing = factory.config().machines().byId(target.id());
        if (existing.isPresent()) {
            MachineRef ref = existing.get().ref();
            if (!factory.config().machines().isRetired(ref.id())) {
                err(player, "这台机器已经接入过了：" + ref.shortId());
                return 0;
            }
            factory.config().machines().setRetired(ref.id(), false);
            refreshObservation(ref);
            wakeLines(factory, ref.id());
            markDirty();
            ok(player, "已从回收站取回：" + ref.shortId() + " · 规则已恢复");
            return 1;
        }
        if (!factory.config().machines().add(target)) {
            err(player, "这台机器已经接入过了：" + target.shortId());
            return 0;
        }
        refreshObservation(target);
        markDirty();
        ok(player, "已接入机器：" + target.shortId() + " @ " + level.dimension().location()
                + " " + pos.toShortString() + " [" + presence.describe() + "]");
        return 1;
    }

    /**
     * 把准星选中的机器断开。
     *
     * <p>规则只有一条：<b>没配过规则就直接删，配过规则就移入回收站</b>。前者零损失（玩家随时可以
     * 重新 connect），后者保住规则（误拆不用重设）。</p>
     *
     * @param player 发起请求的玩家
     * @return 命令结果
     */
    public static int disconnectMachine(ServerPlayer player) {
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
        Machine targetMachine = factory.config().machines().byPosition(level.dimension(), pos);
        if (targetMachine == null) {
            err(player, "这个位置没有接入的机器");
            return 0;
        }
        return detachOrPurge(player, factory, targetMachine.ref());
    }

    /**
     * 按短 id 远程断开机器。
     *
     * @param player  发起请求的玩家
     * @param shortId 短 id（前 8 位）
     * @return 命令结果
     */
    public static int disconnectMachine(ServerPlayer player, String shortId) {
        Factory factory = ownedFactory(player);
        if (factory == null) {
            return 0;
        }
        Optional<Machine> target = factory.config().machines().byShortId(shortId);
        if (target.isEmpty()) {
            err(player, "找不到目标 " + shortId + "（或前缀有歧义）");
            return 0;
        }
        return detachOrPurge(player, factory, target.get().ref());
    }

    /**
     * 断开的唯一出口。
     *
     * @param player  发起请求的玩家
     * @param factory 工厂
     * @param target  机器
     * @return 命令结果
     */
    private static int detachOrPurge(ServerPlayer player, Factory factory, MachineRef target) {
        DetachResult result = detachOrPurgeSilent(factory, target);
        if (result == DetachResult.ALREADY) {
            info(player, "这台机器已经在回收站里了（/idf factory bin 查看）");
            return 1;
        }
        markDirty();
        if (result == DetachResult.PURGED) {
            ok(player, "已删除机器 [" + target.shortId() + "]（这台机器没有配过规则，随时可以 connect 回来）");
            return 1;
        }
        String lines = String.join(" / ", FactoryRefs.lineNamesOf(factory, target.id()));
        int rules = FactoryRefs.ruleCountOf(factory, target.id());
        ok(player, "已移入回收站 [" + target.shortId() + "] · 规则保留（" + lines
                + " · " + rules + " 条）· /idf factory connect 可恢复");
        return 1;
    }

    /**
     * 断开一台机器，不发送聊天回执；供连锁断开汇总用。
     *
     * @param factory 工厂
     * @param target  机器
     * @return 处理结果
     */
    private static DetachResult detachOrPurgeSilent(Factory factory, MachineRef target) {
        if (factory.config().machines().isRetired(target.id())) {
            return DetachResult.ALREADY;
        }
        Set<UUID> referenced = FactoryBin.referenced(factory);
        if (!referenced.contains(target.id())) {
            FactoryBin.purge(factory, target.id());
            return DetachResult.PURGED;
        }
        factory.config().machines().setRetired(target.id(), true);
        return DetachResult.BINNED;
    }

    /** 单台断开的三种结果。 */
    private enum DetachResult {
        /** 没配过规则，已从机器库删除。 */
        PURGED,
        /** 配过规则，已移入回收站。 */
        BINNED,
        /** 本来就在回收站。 */
        ALREADY
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
    // ---- 07. 绑定杖：机器 toggle（toggleMachines / connectMachineTargets / disconnectMachineTargets） ----
    // 负责绑定杖左键的整组开关与逐台执行；由 FactoryWandActionC2S 的 MACHINE_TOGGLE 调用。
    // ------------------------------------------------------------------

    /**
     * 机器 toggle：已连接就断开，未连接就连接；连锁按整组 toggle。
     *
     * <p>整组 toggle 的判定是「所有目标都已连接」才断开；只要有一台没连或还在回收站，
     * 就补齐/取回整组。这样 Ctrl 连点两次不会出现一半连、一半断。</p>
     *
     * @param player 发起请求的玩家
     * @param pos    目标机器
     * @param chain  是否连锁
     * @return 变更的台数
     */
    public static int toggleMachines(ServerPlayer player, BlockPos pos, boolean chain) {
        Factory factory = ownedFactory(player);
        if (factory == null) {
            return 0;
        }
        ServerLevel level = player.serverLevel();
        if (!level.isLoaded(pos)) {
            barErr(player, "目标区块未加载");
            return 0;
        }
        // 轻检查：没有方块实体的不可能是机器。泥土、石头死在这一步，
        // 省掉后面贵的能力探测；注意这只是“跳过贵检查”，结论还是贵检查说了算。
        if (level.getBlockEntity(pos) == null) {
            barErr(player, "该方块不是可连接的机器");
            return 0;
        }
        // 本次操作现算一张占用表：先扫一遍档案，之后每个目标 O(1) 查表。
        // 释放只允许「自己的 + 同角色」；别的工厂、自己另一个角色都拒绝。
        LinkGate gate = LinkGate.of(FactoryRuntime.get().manager());
        LinkGate.ClaimCheck origin =
                gate.check(BlockKey.of(level.dimension(), pos), factory.id(), LinkRole.MACHINE);
        if (origin.blocked()) {
            barErr(player, gateMessage(origin));
            return 0;
        }
        if (!FactoryLinkFinder.isMachine(level, pos)) {
            barErr(player, "该方块不是可连接的机器");
            return 0;
        }
        List<BlockPos> found = chain
                ? FactoryLinkFinder.machines(level, pos)
                : List.of(pos);
        if (found.isEmpty()) {
            barErr(player, "附近没有可连接的机器");
            return 0;
        }
        // BFS 的半径与数量上限按筛选前计算；这里只按占用表分流，过滤后不补位、不重扫。
        GateFilter filter = filterClaimed(gate, factory, LinkRole.MACHINE, level, found);
        List<BlockPos> targets = filter.open();
        if (targets.isEmpty()) {
            barErr(player, filter.blockedRole() > 0 && filter.blockedOther() == 0
                    ? "这些位置已经是你的仓库，先解除" : "这组机器都被别的工厂占了");
            return 0;
        }
        boolean allConnected = true;
        for (BlockPos targetPos : targets) {
            Machine machine = factory.config().machines().byPosition(level.dimension(), targetPos);
            MachineRef ref = machine == null ? null : machine.ref();
            if (ref == null || factory.config().machines().isRetired(ref.id())) {
                allConnected = false;
                break;
            }
        }
        return allConnected
                ? disconnectMachineTargets(player, factory, level, targets, chain,
                        filter.blockedOther(), filter.blockedRole())
                : connectMachineTargets(player, factory, level, targets, chain,
                        filter.blockedOther(), filter.blockedRole());
    }

    private static int connectMachineTargets(ServerPlayer player, Factory factory,
            ServerLevel level, List<BlockPos> targets, boolean chain, int blockedOther,
            int blockedRole) {
        int added = 0;
        int restored = 0;
        int skipped = 0;
        for (BlockPos targetPos : targets) {
            Machine existing = factory.config().machines().byPosition(level.dimension(), targetPos);
            MachineRef target = existing == null ? null : existing.ref();
            if (target != null) {
                if (factory.config().machines().isRetired(target.id())) {
                    factory.config().machines().setRetired(target.id(), false);
                    refreshObservation(target);
                    wakeLines(factory, target.id());
                    restored++;
                } else {
                    skipped++;
                }
                continue;
            }
            MachineRef ref = MachineRef.of(level.dimension(), targetPos);
            if (factory.config().machines().add(ref)) {
                refreshObservation(ref);
                added++;
            } else {
                skipped++;
            }
        }
        if (added + restored > 0) {
            markDirty();
        }
        if (!chain) {
            String label = machineLabel(level, targets.getFirst());
            if (added > 0) {
                barOk(player, "已连接机器 " + label);
            } else if (restored > 0) {
                barOk(player, "已恢复机器 " + label);
            } else {
                barErr(player, "该机器已被连接 " + label);
            }
            return added + restored;
        }
        if (added + restored == 0) {
            barErr(player, "没有新增机器（已连接或已在回收站）");
            return 0;
        }
        String text = "已连接 " + (added + restored) + " 台";
        if (restored > 0) {
            text += "（取回 " + restored + " 台）";
        }
        if (skipped > 0) {
            text += "，跳过 " + skipped + " 台";
        }
        if (blockedOther > 0) {
            text += "，被别家占用 " + blockedOther + " 台";
        }
        if (blockedRole > 0) {
            text += "，角色冲突 " + blockedRole + " 台";
        }
        barOk(player, text);
        return added + restored;
    }

    private static int disconnectMachineTargets(ServerPlayer player, Factory factory,
            ServerLevel level, List<BlockPos> targets, boolean chain, int blockedOther,
            int blockedRole) {
        int purged = 0;
        int binned = 0;
        int already = 0;
        int skipped = 0;
        for (BlockPos targetPos : targets) {
            Machine existing = factory.config().machines().byPosition(level.dimension(), targetPos);
            MachineRef target = existing == null ? null : existing.ref();
            if (target == null) {
                skipped++;
                continue;
            }
            switch (detachOrPurgeSilent(factory, target)) {
                case PURGED -> purged++;
                case BINNED -> binned++;
                case ALREADY -> already++;
            }
        }
        if (purged + binned > 0) {
            markDirty();
        }
        if (!chain) {
            String label = machineLabel(level, targets.getFirst());
            if (purged > 0) {
                barOk(player, "已删除链接 " + label);
            } else if (binned > 0) {
                barErr(player, "该机器仍有 IO 配置，已移入回收站 " + label);
            } else if (already > 0) {
                barErr(player, "该机器已经在回收站 " + label);
            } else {
                barErr(player, "没有可删除的链接");
            }
            return purged + binned;
        }
        if (purged + binned == 0) {
            barErr(player, "没有可删除的链接");
            return 0;
        }
        String text = "已删除 " + (purged + binned) + " 台";
        if (binned > 0) {
            text += "（回收站 " + binned + " 台）";
        }
        if (skipped > 0) {
            text += "，跳过 " + skipped + " 台";
        }
        if (blockedOther > 0) {
            text += "，被别家占用 " + blockedOther + " 台";
        }
        if (blockedRole > 0) {
            text += "，角色冲突 " + blockedRole + " 台";
        }
        barOk(player, text);
        return purged + binned;
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
    // ---- 10. 回收站（binInfo / binRestore / binPurge） ----
    // 负责回收站条目的列出、取回与清理；由 /idf factory bin 的 info、restore 与 purge 调用。
    // ------------------------------------------------------------------

    /**
     * 列出回收站。
     *
     * @param player 发起请求的玩家
     * @return 命令结果
     */
    public static int binInfo(ServerPlayer player) {
        Factory factory = ownedFactory(player);
        if (factory == null) {
            return 0;
        }
        ObservationService observations = observations();
        List<FactorySnapshotBuilder.MachineView> bin =
                FactorySnapshotBuilder.bin(factory, observations);
        if (bin.isEmpty()) {
            info(player, "回收站是空的");
            return 1;
        }
        info(player, "回收站 " + bin.size() + " 台（规则都保留着）：");
        Set<UUID> referenced = FactoryBin.referenced(factory);
        int orphaned = 0;
        for (FactorySnapshotBuilder.MachineView view : bin) {
            MachineRef ref = view.ref();
            String where = ref.dimension().location() + " " + ref.pos().toShortString();
            String reason = view.retired() ? "你放进回收站的" : view.health().describe();
            String rules = view.lineNames().isEmpty()
                    ? "没挂产线"
                    : String.join(" / ", view.lineNames()) + " · " + view.ruleCount() + " 条规则";
            if (!referenced.contains(ref.id())) {
                orphaned++;
            }
            Component row = Component.empty()
                    .append(FactoryMessages.text("  ", FactoryMessages.INFO))
                    .append(FactoryMessages.link(ref.shortId(),
                            "/idf factory bin restore " + ref.shortId(), "取回这台机器"))
                    .append(FactoryMessages.text(" " + where + " · " + reason + " · " + rules,
                            FactoryMessages.INFO));
            FactoryMessages.send(player, row);
        }
        if (orphaned > 0) {
            info(player, "其中 " + orphaned + " 台已无引用 · /idf factory bin purge 可清理");
        }
        return 1;
    }

    /**
     * 把一台机器从回收站取回（只对「已退役」的条目有效）。
     *
     * @param player  发起请求的玩家
     * @param shortId 机器短 id
     * @return 命令结果
     */
    public static int binRestore(ServerPlayer player, String shortId) {
        Factory factory = ownedFactory(player);
        if (factory == null) {
            return 0;
        }
        Optional<Machine> target = factory.config().machines().byShortId(shortId);
        if (target.isEmpty()) {
            err(player, "找不到机器 " + shortId + "（或前缀有歧义）");
            return 0;
        }
        MachineRef ref = target.get().ref();
        if (!factory.config().machines().isRetired(ref.id())) {
            err(player, "这台机器不在回收站（它只是失联了；方块回来会自动恢复）");
            return 0;
        }
        factory.config().machines().setRetired(ref.id(), false);
        Observation observation = refreshObservation(ref);
        wakeLines(factory, ref.id());
        markDirty();
        if (observation != null && !observation.health().alive()) {
            warn(player, "已恢复 [" + ref.shortId() + "]，但方块现在不在（"
                    + observation.health().describe() + "）；放回机器后会自动恢复");
        } else {
            ok(player, "已从回收站取回 [" + ref.shortId() + "] · 规则已恢复");
        }
        return 1;
    }

    /**
     * 清理回收站里所有「已无引用」的条目。
     *
     * @param player 发起请求的玩家
     * @return 命令结果
     */
    public static int binPurge(ServerPlayer player) {
        Factory factory = ownedFactory(player);
        if (factory == null) {
            return 0;
        }
        ObservationService observations = observations();
        List<FactorySnapshotBuilder.MachineView> bin =
                FactorySnapshotBuilder.bin(factory, observations);
        Set<UUID> referenced = FactoryBin.referenced(factory);
        int purged = 0;
        int skipped = 0;
        for (FactorySnapshotBuilder.MachineView view : bin) {
            if (referenced.contains(view.ref().id())) {
                skipped++;
                continue;
            }
            FactoryBin.purge(factory, view.ref().id());
            purged++;
        }
        if (purged > 0) {
            markDirty();
        }
        ok(player, "已清理 " + purged + " 条"
                + (skipped > 0 ? " · " + skipped + " 条仍有引用（先 line <线> remove <机器>）" : ""));
        return 1;
    }

    /**
     * 清理回收站里的一条。仍有引用时拒绝——删规则是 {@code line remove} 的工作。
     *
     * @param player  发起请求的玩家
     * @param shortId 机器短 id
     * @return 命令结果
     */
    public static int binPurge(ServerPlayer player, String shortId) {
        Factory factory = ownedFactory(player);
        if (factory == null) {
            return 0;
        }
        Optional<Machine> target = factory.config().machines().byShortId(shortId);
        if (target.isEmpty()) {
            err(player, "找不到机器 " + shortId + "（或前缀有歧义）");
            return 0;
        }
        MachineRef ref = target.get().ref();
        Health health = FactoryBin.healthOf(factory, observations(), ref.id());
        if (!FactoryBin.binned(factory.config().machines().isRetired(ref.id()),
                health == null ? Health.UNKNOWN : health)) {
            err(player, "这台机器不在回收站");
            return 0;
        }
        if (FactoryBin.referenced(factory).contains(ref.id())) {
            String lines = String.join(" / ", FactoryRefs.lineNamesOf(factory, ref.id()));
            err(player, "这台机器还配在 " + lines + " 上；先 /idf line <线> remove " + ref.shortId());
            return 0;
        }
        FactoryBin.purge(factory, ref.id());
        markDirty();
        ok(player, "已清理回收站条目 [" + ref.shortId() + "]");
        return 1;
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
            warn(player, "仓库当前离线，产线不会搬运");
            return 1;
        }

        Storage storage = factory.live().storage();
        Map<ResourceKey, Long> snapshot = storage.snapshot();
        if (snapshot.isEmpty()) {
            info(player, "仓库是空的");
            return 1;
        }
        List<Map.Entry<ResourceKey, Long>> entries = new ArrayList<>(snapshot.entrySet());
        entries.sort(Map.Entry.<ResourceKey, Long>comparingByValue().reversed());
        int shown = Math.min(entries.size(), INFO_LIMIT);
        for (int i = 0; i < shown; i++) {
            Map.Entry<ResourceKey, Long> entry = entries.get(i);
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
        Storage storage = requireOnlineStorage(player, factory);
        if (storage == null) {
            return 0;
        }
        long extracted = storage.extract(ResourceKey.item(sample), count, false);
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
        Storage storage = requireOnlineStorage(player, factory);
        if (storage == null) {
            return 0;
        }
        ItemStack template = sample.copy();
        template.setCount(1);

        if (player.isCreative()) {
            long inserted = storage.insert(ResourceKey.item(template), count, false);
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
        long accepted = storage.insert(ResourceKey.item(template), want, true);
        if (accepted <= 0) {
            err(player, "仓库收不下 " + itemId(sample));
            return 0;
        }
        long inserted = storage.insert(ResourceKey.item(template), accepted, false);
        int removed = removeFromInventory(player, template, (int) inserted);
        ok(player, "存入 " + removed + " 个 " + itemId(sample));
        return removed > 0 ? 1 : 0;
    }

    // ------------------------------------------------------------------
    // ---- 14. 内部工具：工厂与仓库解析（ownedFactory / requireOnlineStorage / nextId） ----
    // 负责取当前工厂、解析在线仓库、刷新观测、唤醒产线与生成工厂 id；本文件所有公开方法共用。
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
    private static Storage requireOnlineStorage(ServerPlayer player, Factory factory) {
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

    /**
     * 立刻刷新一台机器的观测（接入、取回之后调，别让 {@code info} 看到 UNKNOWN）。
     *
     * @param target 机器
     * @return 刷新后的观测；观测服务未就绪时为 {@code null}
     */
    @Nullable
    private static Observation refreshObservation(MachineRef target) {
        ObservationService observations = observations();
        return observations == null ? null : observations.refresh(target.blockKey());
    }

    /**
     * 唤醒一台机器所在的所有产线（取回、恢复之后调）。
     *
     * @param factory   工厂
     * @param machineId 机器 id
     */
    private static void wakeLines(Factory factory, UUID machineId) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return;
        }
        for (PassiveLine line : factory.config().lines().all()) {
            if (line.containsMachine(machineId)) {
                runtime.lineRunner().wake(line.id());
            }
        }
    }

    /**
     * 生成一个工厂 id。
     *
     * <p>用「拥有者 + 存档自增序号」做确定性 UUID：同一玩家第 N 个工厂的 id 永远一样，
     * 序号单调递增保证不冲突；外面再套一层查重，防御存档被手改过。</p>
     *
     * @param runtime 运行时
     * @param owner   拥有者 uuid
     * @return 可用的工厂 id
     */
    private static UUID nextId(FactoryRuntime runtime, UUID owner) {
        UUID id;
        do {
            long seq = runtime.takeNextSeq();
            id = UUID.nameUUIDFromBytes((owner + ":" + seq).getBytes(StandardCharsets.UTF_8));
        } while (runtime.manager().byId(id).isPresent());
        return id;
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
    private static String describeResource(ResourceKey key) {
        return switch (key.kind()) {
            case FLUID -> key.describe() + "（mB）";
            case ENERGY -> key.describe() + "（FE）";
            default -> key.describe();
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
