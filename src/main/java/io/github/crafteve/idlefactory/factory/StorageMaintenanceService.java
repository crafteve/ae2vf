package io.github.crafteve.idlefactory.factory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.links.LinkRole;
import io.github.crafteve.idlefactory.factory.location.BlockKey;
import io.github.crafteve.idlefactory.factory.observation.ObservationService;
import io.github.crafteve.idlefactory.factory.storage.ContainerAnchor;
import io.github.crafteve.idlefactory.factory.storage.ContainerCapabilityAdapter;
import io.github.crafteve.idlefactory.factory.storage.ContainerRef;
import io.github.crafteve.idlefactory.factory.storage.StorageAnchor;
import io.github.crafteve.idlefactory.factory.storage.StorageResolver;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 容器仓库维护：两步确认清空、容器增删、优先级与已缺失清理。
 *
 * <p>清空确认的表只活在服务端内存，重启丢失；token 绑定玩家，过期即作废。</p>
 *
 * <p>本类不持有运行时单例：档案柜、发布回调、解析器与观测服务都从构造注入。</p>
 */
final class StorageMaintenanceService {
    /** 清空仓库确认的有效期：1200 tick = 60 秒。 */
    private static final long CLEAR_CONFIRM_TICKS = 1200L;
    /** 玩家 → 待确认的清空请求。 */
    private static final Map<UUID, PendingClear> PENDING_CLEARS = new HashMap<>();

    private final FactoryManager manager;
    private final Runnable publish;
    @Nullable
    private final StorageResolver storageResolver;
    @Nullable
    private final ObservationService observations;
    private final StorageLinkService storageLinks;

    /**
     * @param manager         工厂档案柜
     * @param publish         档案变更的唯一出口
     * @param storageResolver 仓库解析器；服务端未就绪时为 {@code null}
     * @param observations    观测服务；服务端未就绪时为 {@code null}
     * @param storageLinks    仓库接线服务，确认清空时复用
     */
    StorageMaintenanceService(FactoryManager manager, Runnable publish,
            @Nullable StorageResolver storageResolver,
            @Nullable ObservationService observations, StorageLinkService storageLinks) {
        this.manager = manager;
        this.publish = publish;
        this.storageResolver = storageResolver;
        this.observations = observations;
        this.storageLinks = storageLinks;
    }

    // ---- 09. 清空确认与标签工具（requestClearStorage / confirmClearStorage / PendingClear） ----
    // 负责清空仓库的两步确认与聊天栏点击指令，并提供机器、方块与模式的显示名；由绑定杖与 /idf storage clear 调用。
    // ------------------------------------------------------------------

    /**
     * 请求清空仓库：先发聊天确认，点击确认后才真正清空。
     *
     * @param player 发起请求的玩家
     * @return 发送确认消息为 {@code 1}
     */
    int requestClearStorage(ServerPlayer player) {
        Factory factory = owned(player);
        if (factory == null) {
            return 0;
        }
        if (factory.config().storageAnchor() == null) {
            FactoryMessages.barErr(player, "当前没有仓库链接");
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
        FactoryMessages.barOk(player, "确认消息已发到聊天栏");
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
    int confirmClearStorage(ServerPlayer player, String token, boolean confirm) {
        PendingClear pending = PENDING_CLEARS.get(player.getUUID());
        if (pending == null || !pending.token().toString().equals(token)) {
            FactoryMessages.barErr(player, "确认已失效，请重新发起");
            return 0;
        }
        if (player.serverLevel().getGameTime() > pending.expiresAt()) {
            PENDING_CLEARS.remove(player.getUUID());
            FactoryMessages.barErr(player, "确认已过期，请重新发起");
            return 0;
        }
        PENDING_CLEARS.remove(player.getUUID());
        if (!confirm) {
            FactoryMessages.barOk(player, "已取消清空仓库");
            return 1;
        }
        return this.storageLinks.clearStorage(player);
    }

    private record PendingClear(UUID token, long expiresAt) {
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
    int bindContainer(ServerPlayer player) {
        Factory factory = owned(player);
        if (factory == null) {
            return 0;
        }
        BlockPos pos = LinkTargetResolver.pickedBlock(player);
        if (pos == null) {
            FactoryMessages.err(player, "没有对准方块（射线 " + (int) LinkTargetResolver.REACH + " 格）");
            return 0;
        }
        ServerLevel level = player.serverLevel();
        if (!level.isLoaded(pos)) {
            FactoryMessages.err(player, "这个位置区块未加载");
            return 0;
        }
        if (!ContainerCapabilityAdapter.isContainer(level, pos)) {
            FactoryMessages.err(player, "这个方块不是仓库容器（物品 / 流体 / 能量）");
            return 0;
        }
        // 指令路径也是绑定入口，必须和绑定杖走同一套占用规则；自占同角色交给下面的
        // contains 判断。
        LinkGate gate = LinkGate.of(this.manager);
        LinkGate.ClaimCheck claim =
                gate.check(BlockKey.of(level.dimension(), pos), factory.id(), LinkRole.STORAGE);
        if (claim.blocked()) {
            FactoryMessages.err(player, LinkGateMessages.gateMessage(claim));
            return 0;
        }

        ContainerRef ref = ContainerRef.of(level.dimension(), pos);
        StorageAnchor current = factory.config().storageAnchor();
        ContainerAnchor anchor;
        if (current == null) {
            anchor = new ContainerAnchor(List.of(ref));
        } else if (current instanceof ContainerAnchor containerAnchor) {
            if (containerAnchor.contains(ref.id())) {
                FactoryMessages.err(player, "这个容器已经绑过了");
                return 0;
            }
            if (containerAnchor.containers().size() >= ContainerAnchor.MAX_CONTAINERS) {
                FactoryMessages.err(player, "一个仓库最多 " + ContainerAnchor.MAX_CONTAINERS + " 个容器");
                return 0;
            }
            anchor = containerAnchor.with(ref);
        } else {
            FactoryMessages.err(player, "当前已绑定 AE2 仓库，先执行 /idf storage unbind 再绑容器");
            return 0;
        }

        factory.bindStorage(anchor, this.storageResolver);
        this.publish.run();
        FactoryMessages.ok(player, "已加入仓库容器 [" + ref.shortId() + "] " + level.dimension().location()
                + " " + pos.toShortString() + "（共 " + anchor.containers().size() + " 个）");
        return 1;
    }

    /**
     * 解绑整个仓库。
     *
     * @param player 发起请求的玩家
     * @return 命令结果
     */
    int unbindStorage(ServerPlayer player) {
        Factory factory = owned(player);
        if (factory == null) {
            return 0;
        }
        if (factory.config().storageAnchor() == null) {
            FactoryMessages.err(player, "还没有绑定仓库");
            return 0;
        }
        factory.bindStorage(null, this.storageResolver);
        this.publish.run();
        FactoryMessages.ok(player, "已解绑仓库");
        return 1;
    }

    /**
     * 从容器仓库里移除一个容器。
     *
     * @param player  发起请求的玩家
     * @param shortId 容器短 id
     * @return 命令结果
     */
    int unbindContainer(ServerPlayer player, String shortId) {
        Factory factory = owned(player);
        if (factory == null) {
            return 0;
        }
        if (!(factory.config().storageAnchor() instanceof ContainerAnchor anchor)) {
            FactoryMessages.err(player, "当前没有容器仓库");
            return 0;
        }
        Optional<ContainerRef> ref = anchor.byShortId(shortId);
        if (ref.isEmpty()) {
            FactoryMessages.err(player, "找不到容器 " + shortId);
            return 0;
        }
        ContainerAnchor next = anchor.without(ref.get().id());
        factory.bindStorage(next.isEmpty() ? null : next, this.storageResolver);
        this.publish.run();
        FactoryMessages.ok(player, "已移除容器 [" + ref.get().shortId() + "]");
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
    int setContainerPriority(ServerPlayer player, String shortId, int priority) {
        Factory factory = owned(player);
        if (factory == null) {
            return 0;
        }
        if (!(factory.config().storageAnchor() instanceof ContainerAnchor anchor)) {
            FactoryMessages.err(player, "当前没有容器仓库");
            return 0;
        }
        Optional<ContainerRef> ref = anchor.byShortId(shortId);
        if (ref.isEmpty()) {
            FactoryMessages.err(player, "找不到容器 " + shortId);
            return 0;
        }
        factory.bindStorage(anchor.withPriority(ref.get().id(), priority), this.storageResolver);
        this.publish.run();
        FactoryMessages.ok(player, "容器 [" + ref.get().shortId() + "] 优先级 → " + priority);
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
    int pruneStorage(ServerPlayer player) {
        Factory factory = owned(player);
        if (factory == null) {
            return 0;
        }
        if (!(factory.config().storageAnchor() instanceof ContainerAnchor anchor)) {
            FactoryMessages.err(player, "当前没有容器仓库（AE2 仓库不需要清理）");
            return 0;
        }
        ObservationService observations = this.observations;
        if (observations == null) {
            FactoryMessages.err(player, "观测服务未就绪");
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
            FactoryMessages.info(player, "没有已缺失的容器（未加载的不算）");
            return 1;
        }
        factory.bindStorage(keep.isEmpty() ? null : new ContainerAnchor(keep), this.storageResolver);
        this.publish.run();
        FactoryMessages.ok(player, "已清理 " + removed + " 个已缺失的容器"
                + (keep.isEmpty() ? " · 仓库已解绑" : " · 剩 " + keep.size() + " 个"));
        return 1;
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
            FactoryMessages.err(player, "你还没有工厂，先执行 /idlefactory create");
        }
        return factory;
    }
}
