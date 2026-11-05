package io.github.crafteve.idlefactory.factory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.node.Node;
import io.github.crafteve.idlefactory.factory.node.NodeManager;
import io.github.crafteve.idlefactory.factory.observation.ObservationService;
import io.github.crafteve.idlefactory.factory.storage.StorageMode;
import io.github.crafteve.idlefactory.factory.storage.StorageResolver;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 工厂生命周期：创建、删除与摘要。
 *
 * <p>一人一个工厂；删除只把工厂从档案里拿掉，机器方块本身不动。摘要的逐行渲染也在本节，
 * 它只读观测快照，<b>不探世界</b>。</p>
 *
 * <p>本类不持有运行时单例：档案柜、发布回调、序号来源与仓库解析器都从构造注入。</p>
 */
final class FactoryLifecycleService {
    /** {@code factory info} 最多列多少台机器。 */
    private static final int INFO_MACHINE_LIMIT = 20;

    private final FactoryManager manager;
    private final Runnable publish;
    private final NodeManager nodeManager;
    private final MachineLinkService machineLinks;
    @Nullable
    private final StorageResolver storageResolver;
    @Nullable
    private final ObservationService observations;
    /** 工厂序号来源：存档自增，取自运行时。 */
    private final LongSupplier nextSeq;

    /**
     * @param manager         工厂档案柜
     * @param publish         档案变更的唯一出口
     * @param nodeManager     虚拟节点管理器
     * @param machineLinks    机器接线服务（删工厂时逐台退样板）
     * @param storageResolver 仓库解析器；服务端未就绪时为 {@code null}
     * @param observations    观测服务；服务端未就绪时为 {@code null}
     * @param nextSeq         工厂序号来源
     */
    FactoryLifecycleService(FactoryManager manager, Runnable publish, NodeManager nodeManager,
            MachineLinkService machineLinks, @Nullable StorageResolver storageResolver,
            @Nullable ObservationService observations, LongSupplier nextSeq) {
        this.manager = manager;
        this.publish = publish;
        this.nodeManager = nodeManager;
        this.machineLinks = machineLinks;
        this.storageResolver = storageResolver;
        this.observations = observations;
        this.nextSeq = nextSeq;
    }

    /**
     * 保证玩家有工厂：没有就静默创建，已有就不动。
     *
     * <p>创建逻辑与 {@link #create(ServerPlayer)} 共用 id 规则，但不发指令回执；自动创建
     * 不应该在第一次打开终端时刷屏。</p>
     *
     * @param player 玩家
     */
    void ensureFactory(ServerPlayer player) {
        if (this.manager.byOwner(player.getUUID()).isPresent()) {
            return;
        }
        UUID id = nextId(player.getUUID());
        Factory factory = new Factory(id,
                new FactoryConfig(player.getUUID(), StorageMode.ME, null));
        if (!this.manager.add(factory)) {
            IdleFactory.LOGGER.warn("自动创建工厂失败：player={}, id={}", player.getUUID(), id);
            return;
        }
        this.publish.run();
    }

    /**
     * 给玩家创建一个工厂。一人一个，已有工厂时不动。
     *
     * @param player 发起请求的玩家
     * @return 命令结果（{@code 1} 成功，{@code 0} 未创建）
     */
    int create(ServerPlayer player) {
        Optional<Factory> existing = this.manager.byOwner(player.getUUID());
        if (existing.isPresent()) {
            FactoryMessages.err(player, "你已经有工厂了：" + existing.get().id());
            return 0;
        }

        UUID id = nextId(player.getUUID());
        Factory factory = new Factory(id,
                new FactoryConfig(player.getUUID(), StorageMode.ME, null));
        if (!this.manager.add(factory)) {
            FactoryMessages.err(player, "工厂创建失败：id 或拥有者冲突");
            return 0;
        }
        this.publish.run();
        FactoryMessages.ok(player, "工厂已创建：" + id);
        return 1;
    }

    /**
     * 删除玩家自己的工厂。机器方块本身不动，只是不再属于任何工厂。
     *
     * @param player 发起请求的玩家
     * @return 命令结果（{@code 1} 成功，{@code 0} 未删除）
     */
    int delete(ServerPlayer player) {
        Optional<Factory> factory = this.manager.byOwner(player.getUUID());
        if (factory.isEmpty()) {
            FactoryMessages.err(player, "你还没有工厂，先执行 /idlefactory create");
            return 0;
        }
        int returned = 0;
        for (Node node : factory.get().config().nodes().all()) {
            returned += this.machineLinks.returnPatterns(factory.get(), node, player);
        }
        this.manager.remove(factory.get().id());
        this.nodeManager.removeFactory(factory.get().id());
        this.publish.run();
        FactoryMessages.ok(player, "工厂已删除：" + factory.get().id()
                + FactoryMessages.patternSuffix(returned));
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
    int info(ServerPlayer player) {
        Factory factory = owned(player);
        if (factory == null) {
            return 0;
        }
        // 即时刷新一次，别让 online 停在 tick 定时器上一秒的状态
        factory.refreshStorage(this.storageResolver);
        FactoryMessages.info(player, FactorySnapshotBuilder.describe(factory));

        List<FactorySnapshotBuilder.MachineView> machines =
                FactorySnapshotBuilder.machines(factory, this.observations,
                        player.serverLevel().getGameTime());
        if (machines.isEmpty()) {
            FactoryMessages.info(player, "节点库为空：准星对准机器执行 /idf factory connect");
            return 1;
        }
        FactoryMessages.info(player, FactorySnapshotBuilder.health(factory, machines).describe());

        int offlineCount = 0;
        for (FactorySnapshotBuilder.MachineView view : machines) {
            if (!view.health().isOnline()) {
                offlineCount++;
            }
        }
        int shown = 0;
        for (FactorySnapshotBuilder.MachineView view : machines) {
            if (shown >= INFO_MACHINE_LIMIT) {
                FactoryMessages.info(player, "… 还有 " + (machines.size() - shown) + " 台");
                break;
            }
            sendMachineRow(player, view);
            shown++;
        }
        if (offlineCount > 0) {
            FactoryMessages.info(player, "离线 " + offlineCount + " 台 · 放回方块会自动恢复；删除请用机器库界面");
        }
        return 1;
    }

    private static void sendMachineRow(ServerPlayer player, FactorySnapshotBuilder.MachineView view) {
        String where = view.dimension().location() + " " + view.pos().toShortString();
        String status = FactorySnapshotBuilder.describe(view);
        StringBuilder hover = new StringBuilder(where).append('\n').append(status)
                .append(" · 规则 ").append(view.ruleCount()).append(" 条");
        Component row = Component.empty()
                .append(FactoryMessages.text("  ", FactoryMessages.INFO))
                .append(FactoryMessages.hover(view.machineId().toString().substring(0, 8),
                        hover.toString()))
                .append(FactoryMessages.text(" " + where + "  " + status, FactoryMessages.INFO));
        FactoryMessages.send(player, row);
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

    /**
     * 生成一个工厂 id。
     *
     * <p>用「拥有者 + 存档自增序号」做确定性 UUID：同一玩家第 N 个工厂的 id 永远一样，
     * 序号单调递增保证不冲突；外面再套一层查重，防御存档被手改过。</p>
     *
     * @param owner 拥有者 uuid
     * @return 可用的工厂 id
     */
    private UUID nextId(UUID owner) {
        UUID id;
        do {
            long seq = this.nextSeq.getAsLong();
            id = UUID.nameUUIDFromBytes((owner + ":" + seq).getBytes(StandardCharsets.UTF_8));
        } while (this.manager.byId(id).isPresent());
        return id;
    }
}
