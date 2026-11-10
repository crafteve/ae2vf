package io.github.crafteve.idlefactory.network.payload;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryRuntime;
import io.github.crafteve.idlefactory.factory.FactoryService;
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
 * 存储绑定杖的操作请求：仓库 toggle 与切换模式。
 *
 * <p>机器绑定杖的请求有独立包，见 {@code network.payload.wand}。客户端只发「我点了什么」，
 * 服务端负责校验主手物品、工厂所有权与目标类型。处理完无论成功失败都回一份
 * {@link FactoryLinksS2C}，让客户端缓存和服务端一致。</p>
 *
 * @param action 动作
 * @param pos    目标方块；清空请求/切换模式时为 {@code null}
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
        switch (payload.action()) {
            case STORAGE_TOGGLE -> {
                if (pos != null && player.getMainHandItem().getItem()
                        instanceof StorageBindingWandItem) {
                    FactoryService.toggleStorage(player, pos, payload.chain());
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

    /** 存储绑定杖动作。 */
    public enum Action {
        /** 仓库 toggle：已绑定则取消，未绑定则绑定；连锁按整组 toggle。 */
        STORAGE_TOGGLE,
        /** 切换 ME / 容器模式。 */
        MODE_SWITCH
    }
}
