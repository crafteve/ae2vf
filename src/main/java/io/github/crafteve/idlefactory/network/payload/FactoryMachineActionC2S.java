package io.github.crafteve.idlefactory.network.payload;

import java.util.Optional;
import java.util.UUID;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryRuntime;
import io.github.crafteve.idlefactory.factory.FactoryService;
import io.github.crafteve.idlefactory.factory.NodeActions;
import io.github.crafteve.idlefactory.menu.FactoryGuiOpening;
import io.github.crafteve.idlefactory.network.FactoryTableSnapshots;

import io.netty.handler.codec.DecoderException;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端对一台机器做出的管理动作。
 *
 * <p>P1 删除产线后，界面上还能改的机器状态只剩「删除这台机器」：新建 / 重命名 / 启停产线、
 * 把机器放进或移出产线都随产线一起删除。IO 规则的增删改仍走 {@link FactoryRuleActionC2S}。</p>
 *
 * <p>只传机器 id；是否存在、是否还有规则，一律由服务端重新校验。</p>
 *
 * @param containerId 菜单容器 id
 * @param machineId   目标机器 id
 */
public record FactoryMachineActionC2S(int containerId, UUID machineId)
        implements CustomPacketPayload {

    /** 包类型：{@code idlefactory:factory_machine_action}。 */
    public static final Type<FactoryMachineActionC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID,
                    "factory_machine_action"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, FactoryMachineActionC2S> STREAM_CODEC =
            StreamCodec.of(FactoryMachineActionC2S::write, FactoryMachineActionC2S::read);

    @Override
    public Type<FactoryMachineActionC2S> type() {
        return TYPE;
    }

    /**
     * 服务端处理：先校验菜单，再交给动作层，最后回反馈并按需推新快照。
     *
     * @param payload 动作
     * @param context 上下文
     */
    public static void handle(FactoryMachineActionC2S payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!FactoryGuiOpening.isFactoryMenu(player.containerMenu, payload.containerId())) {
                return;
            }
            FactoryRuntime runtime = FactoryRuntime.get();
            if (runtime == null) {
                sendFeedback(player, payload.containerId(), false, "Idle Factory 尚未启动");
                return;
            }
            Optional<Factory> factory = runtime.manager().byOwner(player.getUUID());
            if (factory.isEmpty()) {
                sendFeedback(player, payload.containerId(), false, "你还没有工厂");
                return;
            }
            NodeActions.Result result = FactoryService.deleteMachineById(
                    factory.get(), payload.machineId());
            sendFeedback(player, payload.containerId(), result.ok(), result.message());
            if (result.ok()) {
                FactoryTableSnapshots.send(player, payload.containerId());
            }
        });
    }

    private static void sendFeedback(ServerPlayer player, int containerId, boolean ok,
            String message) {
        PacketDistributor.sendToPlayer(player, new FactoryEditFeedbackS2C(
                containerId, ok, FactoryEditFeedbackS2C.clip(message), null));
    }

    private static void write(RegistryFriendlyByteBuf buf, FactoryMachineActionC2S payload) {
        buf.writeVarInt(payload.containerId());
        buf.writeUUID(payload.machineId());
    }

    private static FactoryMachineActionC2S read(RegistryFriendlyByteBuf buf) {
        int containerId = buf.readVarInt();
        UUID machineId = buf.readUUID();
        if (machineId == null) {
            throw new DecoderException("machine action requires a machine id");
        }
        return new FactoryMachineActionC2S(containerId, machineId);
    }
}
