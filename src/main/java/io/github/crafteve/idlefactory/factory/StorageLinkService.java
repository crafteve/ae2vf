package io.github.crafteve.idlefactory.factory;

import java.util.List;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.links.LinkRole;
import io.github.crafteve.idlefactory.factory.location.BlockKey;
import io.github.crafteve.idlefactory.factory.storage.ContainerAnchor;
import io.github.crafteve.idlefactory.factory.storage.ContainerCapabilityAdapter;
import io.github.crafteve.idlefactory.factory.storage.ContainerRef;
import io.github.crafteve.idlefactory.factory.storage.MeAnchor;
import io.github.crafteve.idlefactory.factory.storage.StorageAnchor;
import io.github.crafteve.idlefactory.factory.storage.StorageMode;
import io.github.crafteve.idlefactory.factory.storage.StorageResolver;
import io.github.crafteve.idlefactory.integration.ae2.Ae2Binder;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 仓库接线：清空仓库、切换 ME / 容器模式、绑定杖的仓库 toggle。
 *
 * <p>它只写仓库地址与模式，不解析仓库内容、不做摘要。占用门禁与文案分别交给
 * {@link LinkGate} 与 {@link LinkGateMessages}。</p>
 *
 * <p>本类不持有运行时单例：档案柜、发布回调与解析器都从构造注入。</p>
 */
final class StorageLinkService {
    private final FactoryManager manager;
    private final Consumer<Factory> publish;
    @Nullable
    private final StorageResolver storageResolver;

    /**
     * @param manager         工厂档案柜
     * @param publish         档案变更的唯一出口；仓库坐标写进快照，所以走推送版
     * @param storageResolver 仓库解析器；服务端未就绪时为 {@code null}
     */
    StorageLinkService(FactoryManager manager, Consumer<Factory> publish,
            @Nullable StorageResolver storageResolver) {
        this.manager = manager;
        this.publish = publish;
        this.storageResolver = storageResolver;
    }

    // ---- 06. 模式切换与仓库 toggle（switchStorageMode / toggleStorage） ----
    // 两条入口都由绑定杖触发。清空仓库链接原本还有一条聊天确认流程，随 /idf 指令一起删除；
    // 现在要解除绑定只能靠绑定杖对同一个方块再 toggle 一次。
    // ------------------------------------------------------------------

    /**
     * 切换 ME / 容器模式；有链接时拒绝。
     *
     * @param player 发起请求的玩家
     * @return 成功为 {@code 1}
     */
    int switchStorageMode(ServerPlayer player) {
        Factory factory = owned(player);
        if (factory == null) {
            return 0;
        }
        if (factory.config().storageAnchor() != null) {
            FactoryMessages.barErr(player, "先清空当前链接再切换模式");
            return 0;
        }
        StorageMode next = factory.config().storageMode() == StorageMode.ME
                ? StorageMode.CONTAINER : StorageMode.ME;
        factory.setStorageMode(next);
        this.publish.accept(factory);
        FactoryMessages.barOk(player, "已切换为 " + FactoryMessages.modeLabel(next) + " 模式");
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
    int toggleStorage(ServerPlayer player, BlockPos pos, boolean chain) {
        Factory factory = owned(player);
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

    private int toggleMeStorage(ServerPlayer player, Factory factory, ServerLevel level,
            StorageAnchor anchor, BlockPos pos) {
        // 先查占用：释放只允许「自己的 + 仓库」角色；别的工厂、自己的机器都拒绝。
        LinkGate gate = LinkGate.of(this.manager);
        LinkGate.ClaimCheck claim =
                gate.check(BlockKey.of(level.dimension(), pos), factory.id(), LinkRole.STORAGE);
        if (claim.blocked()) {
            FactoryMessages.barErr(player, LinkGateMessages.gateMessage(claim));
            return 0;
        }
        boolean same = anchor instanceof MeAnchor me
                && me.dimension().equals(level.dimension()) && me.pos().equals(pos);
        if (same) {
            factory.bindStorage(null, this.storageResolver);
            this.publish.accept(factory);
            FactoryMessages.barOk(player, "已取消 ME 仓库链接");
            return 1;
        }
        if (anchor != null) {
            FactoryMessages.barErr(player, "已绑定其他仓库，先右键空气清空");
            return 0;
        }
        if (!level.isLoaded(pos)) {
            FactoryMessages.barErr(player, "目标区块未加载");
            return 0;
        }
        if (Ae2Binder.nodeAt(level, pos) == null) {
            FactoryMessages.barErr(player, "该方块没有接入 ME 网络");
            return 0;
        }
        factory.bindStorage(new MeAnchor(level.dimension(), pos), this.storageResolver);
        this.publish.accept(factory);
        FactoryMessages.barOk(player, "已绑定 ME 仓库 " + FactoryMessages.blockLabel(level, pos));
        return 1;
    }

    private int toggleContainerStorage(ServerPlayer player, Factory factory,
            ServerLevel level, StorageAnchor anchor, BlockPos pos, boolean chain) {
        if (anchor instanceof MeAnchor) {
            FactoryMessages.barErr(player, "当前是 ME 模式，先清空当前链接");
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
        LinkGate gate = LinkGate.of(this.manager);
        LinkGate.ClaimCheck origin =
                gate.check(BlockKey.of(level.dimension(), pos), factory.id(), LinkRole.STORAGE);
        if (origin.blocked()) {
            FactoryMessages.barErr(player, LinkGateMessages.gateMessage(origin));
            return 0;
        }
        // BFS 的半径与数量上限按筛选前计算；这里只按占用表分流，过滤后不补位、不重扫。
        LinkGate.Filter filter = gate.filter(factory.id(), LinkRole.STORAGE, level.dimension(), found);
        List<BlockPos> targets = filter.open();
        if (targets.isEmpty()) {
            FactoryMessages.barErr(player, "这些位置都不能绑定");
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

    private int bindContainerTargets(ServerPlayer player, Factory factory,
            ServerLevel level, ContainerAnchor current, List<BlockPos> targets, boolean chain,
            int blockedOther, int blockedRole) {
        if (!level.isLoaded(targets.getFirst())) {
            FactoryMessages.barErr(player, "目标区块未加载");
            return 0;
        }
        BlockPos origin = targets.getFirst();
        if (Ae2Binder.nodeAt(level, origin) != null) {
            FactoryMessages.barErr(player, "这是 ME 网络方块，请切到 ME 模式");
            return 0;
        }
        if (!ContainerCapabilityAdapter.isContainer(level, origin)) {
            FactoryMessages.barErr(player, "该方块不是仓库容器");
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
                FactoryMessages.barErr(player, "这些容器都被别的工厂占了");
            } else if (blockedRole > 0) {
                FactoryMessages.barErr(player, "这些位置已经是你的机器，先解除");
            } else {
                FactoryMessages.barErr(player, "没有新增容器（已绑过或达到上限 "
                        + ContainerAnchor.MAX_CONTAINERS + " 个）");
            }
            return 0;
        }
        factory.bindStorage(next, this.storageResolver);
        this.publish.accept(factory);
        if (!chain) {
            FactoryMessages.barOk(player, "已绑定容器 " + FactoryMessages.blockLabel(level, origin));
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
        FactoryMessages.barOk(player, text);
        return added;
    }

    private int unbindContainerTargets(ServerPlayer player, Factory factory,
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
            FactoryMessages.barErr(player, "该位置没有绑定容器");
            return 0;
        }
        factory.bindStorage(next.isEmpty() ? null : next, this.storageResolver);
        factory.setStorageMode(StorageMode.CONTAINER);
        this.publish.accept(factory);
        if (!chain) {
            String label = FactoryMessages.blockLabel(level, targets.getFirst());
            FactoryMessages.barOk(player, next.isEmpty()
                    ? "已取消容器链接 " + label + "，仓库已清空"
                    : "已取消容器链接 " + label);
            return removed;
        }
        FactoryMessages.barOk(player, "已取消 " + removed + " 个容器"
                + (next.isEmpty() ? "，仓库已清空" : "，剩余 " + next.containers().size() + " 个"));
        return removed;
    }

    /**
     * 取玩家自己的工厂；没有就提示。
     *
     * @param player 玩家
     * @return 工厂；玩家没有工厂时为 {@code null}
     */
    @Nullable
    private Factory owned(ServerPlayer player) {
        Factory factory = FactoryRefs.owned(this.manager, player);
        if (factory == null) {
            FactoryMessages.err(player, "你还没有工厂：打开一次终端会自动创建");
        }
        return factory;
    }
}
