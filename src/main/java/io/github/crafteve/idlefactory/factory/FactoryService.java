package io.github.crafteve.idlefactory.factory;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import io.github.crafteve.idlefactory.factory.storage.WarehouseLink;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 工厂行政窗口：所有「创建 / 删除 / 查询」的入口。
 *
 * <p>终端、指令、以后的 WebUI 都走这里。它负责权限（第一版只看 owner）和档案写入，
 * 不负责界面，也不负责搬运。</p>
 */
public final class FactoryService {
    private FactoryService() {
        // 纯静态工具类
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
            player.sendSystemMessage(Component.literal("Idle Factory 尚未启动（服务端未就绪）"));
            return 0;
        }
        Optional<Factory> existing = runtime.manager().byOwner(player.getUUID());
        if (existing.isPresent()) {
            player.sendSystemMessage(Component.literal(
                    "你已经有工厂了：" + existing.get().id()));
            return 0;
        }

        UUID id = nextId(runtime, player.getUUID());
        Factory factory = new Factory(id, player.getUUID());
        if (!runtime.manager().add(factory)) {
            player.sendSystemMessage(Component.literal("工厂创建失败：id 或拥有者冲突"));
            return 0;
        }
        runtime.markDirty();
        player.sendSystemMessage(Component.literal("工厂已创建：" + id));
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
            player.sendSystemMessage(Component.literal("Idle Factory 尚未启动（服务端未就绪）"));
            return 0;
        }
        Optional<Factory> factory = runtime.manager().byOwner(player.getUUID());
        if (factory.isEmpty()) {
            player.sendSystemMessage(Component.literal("你还没有工厂，先执行 /idlefactory create"));
            return 0;
        }
        runtime.manager().remove(factory.get().id());
        runtime.markDirty();
        player.sendSystemMessage(Component.literal("工厂已删除：" + factory.get().id()));
        return 1;
    }

    /**
     * 打印玩家自己的工厂摘要。
     *
     * @param player 发起请求的玩家
     * @return 命令结果（{@code 1} 有工厂，{@code 0} 没有）
     */
    public static int info(ServerPlayer player) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            player.sendSystemMessage(Component.literal("Idle Factory 尚未启动（服务端未就绪）"));
            return 0;
        }
        Optional<Factory> factory = runtime.manager().byOwner(player.getUUID());
        if (factory.isEmpty()) {
            player.sendSystemMessage(Component.literal("你还没有工厂，先执行 /idlefactory create"));
            return 0;
        }
        player.sendSystemMessage(Component.literal(FactorySnapshotBuilder.describe(factory.get())));
        return 1;
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

    /**
     * 把玩家身上的奇点频率绑到工厂上。第一版留作后续接入点：需要「绑定时读玩家附件」的
     * 逻辑，等 AE2 适配层补上 {@code WarehouseResolver} 后再开。
     *
     * @param factory 工厂
     * @param anchor  仓库地址
     */
    public static void bind(Factory factory, io.github.crafteve.idlefactory.factory.storage.WarehouseAnchor anchor) {
        factory.bindAnchor(anchor);
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime != null) {
            runtime.markDirty();
        }
    }

    /** 给界面 / 命令用的仓库在线状态。 */
    public static boolean warehouseOnline(Factory factory) {
        WarehouseLink link = factory.warehouse();
        return link.online();
    }
}
