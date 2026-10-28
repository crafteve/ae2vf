package io.github.crafteve.idlefactory.network.payload;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryRuntime;
import io.github.crafteve.idlefactory.factory.FactoryService;
import io.github.crafteve.idlefactory.factory.storage.StorageMode;
import io.github.crafteve.idlefactory.item.MachineBindingWandItem;
import io.github.crafteve.idlefactory.item.StorageBindingWandItem;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 绑定杖的操作请求：机器连接/断开、仓库绑定/取消/清空、切换模式。
 *
 * <p>客户端只发“我点了什么”，服务端负责校验主手物品、工厂所有权、距离、目标类型和上限。
 * 处理完无论成功失败都回一份 {@link FactoryLinksS2C}，让客户端缓存和服务端一致。</p>
 *
 * @param action 动作
 * @param pos    目标方块；清空/切换模式时为 {@code null}
 * @param chain  是否连锁
 */
public record FactoryWandActionC2S(Action action, @Nullable BlockPos pos, boolean chain)
        implements CustomPacketPayload {
    /** 包类型：{@code idlefactory:factory_wand_action}。 */
    public static final Type<FactoryWandActionC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID,
                    "factory_wand_action"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, FactoryWandActionC2S> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeEnum(payload.action());
                        buf.writeBoolean(payload.pos() != null);
                        if (payload.pos() != null) {
                            buf.writeBlockPos(payload.pos());
                        }
                        buf.writeBoolean(payload.chain());
                    },
                    buf -> new FactoryWandActionC2S(buf.readEnum(Action.class),
                            buf.readBoolean() ? buf.readBlockPos() : null,
                            buf.readBoolean()));

    @Override
    public Type<FactoryWandActionC2S> type() {
        return TYPE;
    }

    /**
     * 服务端处理：按动作转给 {@link FactoryService}，再回同步包。
     *
     * @param payload 请求
     * @param context 上下文
     */
    public static void handle(FactoryWandActionC2S payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            handleAction(player, payload);
            sendLinks(player);
        });
    }

    private static void handleAction(ServerPlayer player, FactoryWandActionC2S payload) {
        BlockPos pos = payload.pos();
        Factory factory = factoryOf(player);
        switch (payload.action()) {
            case MACHINE_CONNECT -> {
                if (pos != null && player.getMainHandItem().getItem()
                        instanceof MachineBindingWandItem) {
                    FactoryService.connectMachines(player, pos, payload.chain());
                }
            }
            case MACHINE_DISCONNECT -> {
                if (pos != null && player.getMainHandItem().getItem()
                        instanceof MachineBindingWandItem) {
                    FactoryService.disconnectMachines(player, pos, payload.chain());
                }
            }
            case STORAGE_BIND -> {
                if (pos == null || factory == null
                        || !(player.getMainHandItem().getItem()
                        instanceof StorageBindingWandItem)) {
                    return;
                }
                if (factory.storageMode() == StorageMode.ME) {
                    FactoryService.bindMeStorage(player, pos);
                } else {
                    FactoryService.bindContainers(player, pos, payload.chain());
                }
            }
            case STORAGE_UNBIND -> {
                if (factory == null || !(player.getMainHandItem().getItem()
                        instanceof StorageBindingWandItem)) {
                    return;
                }
                if (factory.storageMode() == StorageMode.CONTAINER && pos != null) {
                    FactoryService.unbindContainers(player, pos, payload.chain());
                } else if (factory.storageMode() == StorageMode.ME && pos != null) {
                    FactoryService.clearStorageAt(player, pos);
                } else {
                    FactoryService.clearStorage(player);
                }
            }
            case STORAGE_CLEAR -> {
                if (player.getMainHandItem().getItem() instanceof StorageBindingWandItem) {
                    FactoryService.clearStorage(player);
                }
            }
            case MODE_SWITCH -> {
                if (player.getMainHandItem().getItem() instanceof StorageBindingWandItem) {
                    FactoryService.switchStorageMode(player);
                }
            }
        }
    }

    private static void sendLinks(ServerPlayer player) {
        Factory factory = factoryOf(player);
        PacketDistributor.sendToPlayer(player, factory == null
                ? FactoryLinksS2C.empty() : FactoryLinksS2C.of(factory));
    }

    @Nullable
    private static Factory factoryOf(ServerPlayer player) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return null;
        }
        return runtime.manager().byOwner(player.getUUID()).orElse(null);
    }

    /** 绑定杖动作。 */
    public enum Action {
        /** 连接机器。 */
        MACHINE_CONNECT,
        /** 断开机器。 */
        MACHINE_DISCONNECT,
        /** 绑定仓库（服务端按当前模式分 ME / 容器）。 */
        STORAGE_BIND,
        /** 取消仓库绑定（ME 清空，容器按目标取消）。 */
        STORAGE_UNBIND,
        /** 清空仓库。 */
        STORAGE_CLEAR,
        /** 切换 ME / 容器模式。 */
        MODE_SWITCH
    }
}
