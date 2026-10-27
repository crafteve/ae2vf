package io.github.crafteve.idlefactory.network.payload;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryRuntime;
import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder;
import io.github.crafteve.idlefactory.menu.FactoryMenu;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;
import java.util.Optional;

/**
 * 客户端请求当前工厂的机器表。
 *
 * <p>P1 的第一小步只做「左表真实数据」：界面每 20 tick 请求一次，服务端回一份
 * {@link FactoryTableS2C}。没有订阅、没有增量、没有动作包。</p>
 *
 * @param containerId 菜单容器 id，服务端用它拒绝过期界面
 */
public record FactoryTableRequestC2S(int containerId) implements CustomPacketPayload {
    /** 包类型：{@code idlefactory:factory_table_request}。 */
    public static final Type<FactoryTableRequestC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID,
                    "factory_table_request"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, FactoryTableRequestC2S> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeVarInt(payload.containerId()),
                    buf -> new FactoryTableRequestC2S(buf.readVarInt()));

    @Override
    public Type<FactoryTableRequestC2S> type() {
        return TYPE;
    }

    /**
     * 服务端处理：校验容器，构建并回一份机器表。
     *
     * @param payload 请求
     * @param context 上下文
     */
    public static void handle(FactoryTableRequestC2S payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!(player.containerMenu instanceof FactoryMenu menu)
                    || menu.containerId != payload.containerId()) {
                return;
            }
            FactoryRuntime runtime = FactoryRuntime.get();
            if (runtime == null) {
                PacketDistributor.sendToPlayer(player, FactoryTableS2C.empty(payload.containerId()));
                return;
            }
            Optional<Factory> factory = runtime.manager().byOwner(player.getUUID());
            if (factory.isEmpty()) {
                PacketDistributor.sendToPlayer(player, FactoryTableS2C.empty(payload.containerId()));
                return;
            }
            Factory owner = factory.get();
            List<FactorySnapshotBuilder.MachineView> machines =
                    FactorySnapshotBuilder.machines(owner, runtime.observations(),
                            player.serverLevel().getGameTime());
            FactorySnapshotBuilder.FactoryHealth health =
                    FactorySnapshotBuilder.health(owner, machines);
            PacketDistributor.sendToPlayer(player, new FactoryTableS2C(
                    payload.containerId(), true, owner.state(),
                    owner.storageLink().online(), health.lines(), health.runningLines(),
                    machines));
        });
    }
}
