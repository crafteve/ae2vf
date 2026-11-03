package io.github.crafteve.idlefactory.network.payload;

import java.util.Optional;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryRuntime;
import io.github.crafteve.idlefactory.menu.FactoryGuiOpening;
import io.github.crafteve.idlefactory.network.FactoryStorageSnapshots;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端请求当前仓库摘要。
 *
 * <p>只在仓库 tab 打开时请求；服务端按数量降序回前 1024 条。没有工厂或离线就回空表。</p>
 *
 * @param containerId 菜单容器 id
 */
public record FactoryStorageRequestC2S(int containerId) implements CustomPacketPayload {
    /** 包类型：{@code idlefactory:factory_storage_request}。 */
    public static final Type<FactoryStorageRequestC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID,
                    "factory_storage_request"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, FactoryStorageRequestC2S> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeVarInt(payload.containerId()),
                    buf -> new FactoryStorageRequestC2S(buf.readVarInt()));

    @Override
    public Type<FactoryStorageRequestC2S> type() {
        return TYPE;
    }

    /**
     * 服务端处理：校验容器，读仓库快照并回包。
     *
     * @param payload 请求
     * @param context 上下文
     */
    public static void handle(FactoryStorageRequestC2S payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!FactoryGuiOpening.isFactoryMenu(player.containerMenu, payload.containerId())) {
                return;
            }
            FactoryRuntime runtime = FactoryRuntime.get();
            if (runtime == null) {
                PacketDistributor.sendToPlayer(player,
                        FactoryStorageS2C.empty(payload.containerId()));
                return;
            }
            Optional<Factory> optional = runtime.manager().byOwner(player.getUUID());
            if (optional.isEmpty() || !optional.get().live().storageOnline()) {
                PacketDistributor.sendToPlayer(player,
                        FactoryStorageS2C.empty(payload.containerId()));
                return;
            }
            FactoryStorageSnapshots.send(player, optional.get(), payload.containerId());
        });
    }
}
