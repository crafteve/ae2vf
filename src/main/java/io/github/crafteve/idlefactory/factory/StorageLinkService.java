package io.github.crafteve.idlefactory.factory;

import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.links.LinkRole;
import io.github.crafteve.idlefactory.integration.ae2.Ae2Binder;
import io.github.crafteve.idlefactory.location.BlockKey;
import io.github.crafteve.idlefactory.storage.MeStorageResolver;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 仓库接线：绑定杖对 ME 仓库方块 toggle 绑定 / 解绑。
 *
 * <p>仓库只有 ME 一种，所以这里不再有模式切换与容器连锁：一个工厂最多记一个锚点方块，
 * 对着同一个方块再点一次就是解绑。它只写锚点，不解析仓库内容、不做摘要；占用门禁与文案
 * 分别交给 {@link LinkGate} 与 {@link LinkGateMessages}。</p>
 *
 * <p>本类不持有运行时单例：档案柜、发布回调与解析器都从构造注入。</p>
 */
final class StorageLinkService {
    private final FactoryManager manager;
    private final Consumer<Factory> publish;
    @Nullable
    private final MeStorageResolver storageResolver;

    /**
     * @param manager         工厂档案柜
     * @param publish         档案变更的唯一出口；仓库坐标写进快照，所以走推送版
     * @param storageResolver 仓库解析器；服务端未就绪时为 {@code null}
     */
    StorageLinkService(FactoryManager manager, Consumer<Factory> publish,
            @Nullable MeStorageResolver storageResolver) {
        this.manager = manager;
        this.publish = publish;
        this.storageResolver = storageResolver;
    }

    /**
     * 仓库 toggle：没绑过就绑到目标方块，已绑同一个方块就解绑。
     *
     * @param player 发起请求的玩家
     * @param pos    目标方块
     * @return 变更数量
     */
    int toggleStorage(ServerPlayer player, BlockPos pos) {
        Factory factory = owned(player);
        if (factory == null) {
            return 0;
        }
        ServerLevel level = player.serverLevel();
        BlockKey target = BlockKey.of(level.dimension(), pos);
        // 先查占用：释放只允许「自己的 + 仓库」角色；别的工厂、自己的机器都拒绝。
        LinkGate gate = LinkGate.of(this.manager);
        LinkGate.ClaimCheck claim = gate.check(target, factory.id(), LinkRole.STORAGE);
        if (claim.blocked()) {
            FactoryMessages.barErr(player, LinkGateMessages.gateMessage(claim));
            return 0;
        }
        BlockKey anchor = factory.config().storageAnchor();
        if (target.equals(anchor)) {
            factory.bindStorage(null, this.storageResolver);
            this.publish.accept(factory);
            FactoryMessages.barOk(player, "已取消 ME 仓库链接");
            return 1;
        }
        if (anchor != null) {
            FactoryMessages.barErr(player, "已绑定其他仓库，先对着已绑方块再点一次解绑");
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
        factory.bindStorage(target, this.storageResolver);
        this.publish.accept(factory);
        FactoryMessages.barOk(player, "已绑定 ME 仓库 " + FactoryMessages.blockLabel(level, pos));
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
            FactoryMessages.err(player, "你还没有工厂：打开一次终端会自动创建");
        }
        return factory;
    }
}
