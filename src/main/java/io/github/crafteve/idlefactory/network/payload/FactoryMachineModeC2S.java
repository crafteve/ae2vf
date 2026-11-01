package io.github.crafteve.idlefactory.network.payload;

import java.util.Optional;
import java.util.UUID;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryRuntime;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.machine.MachineMode;
import io.github.crafteve.idlefactory.menu.FactoryPatternTerminalMenu;
import io.github.crafteve.idlefactory.menu.FactoryTerminalMenus;
import io.github.crafteve.idlefactory.network.FactoryTableSnapshots;
import io.github.crafteve.idlefactory.network.FactoryTerminalSnapshots;

import io.netty.handler.codec.DecoderException;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端切换一台机器的主动 / 被动模式。
 *
 * <p>服务端只接受「容器 id 对得上、机器属于该玩家工厂」的请求；模式切换后立即刷新 CPU
 * 广告，并把机器表与目标详情推回客户端，避免左面板停在旧状态。</p>
 *
 * @param containerId 菜单容器 id
 * @param machineId   机器 id
 * @param mode        新模式
 */
public record FactoryMachineModeC2S(int containerId, UUID machineId,
        MachineMode mode) implements CustomPacketPayload {
    /** 包类型：{@code idlefactory:machine_mode}。 */
    public static final Type<FactoryMachineModeC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID, "machine_mode"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, FactoryMachineModeC2S> STREAM_CODEC =
            StreamCodec.of(FactoryMachineModeC2S::write, FactoryMachineModeC2S::read);

    @Override
    public Type<FactoryMachineModeC2S> type() {
        return TYPE;
    }

    /**
     * 服务端处理：校验菜单与归属，写模式并刷新。
     *
     * @param payload 请求
     * @param context 上下文
     */
    public static void handle(FactoryMachineModeC2S payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!FactoryTerminalMenus.isFactoryMenu(player.containerMenu, payload.containerId())) {
                return;
            }
            FactoryRuntime runtime = FactoryRuntime.get();
            if (runtime == null) {
                return;
            }
            Optional<Factory> optional = runtime.manager().byOwner(player.getUUID());
            if (optional.isEmpty()) {
                return;
            }
            Machine machine = optional.get().config().machines()
                    .byId(payload.machineId()).orElse(null);
            if (machine == null) {
                return;
            }
            boolean changed = machine.provider().selectMode(payload.mode());
            if (changed) {
                runtime.markDirty();
            }
            runtime.nodeManager().refreshPatterns(machine.id());
            FactoryTableSnapshots.send(player, payload.containerId());
            int page = player.containerMenu instanceof FactoryPatternTerminalMenu pattern
                    ? pattern.page() : 0;
            FactoryTerminalSnapshots.sendDetail(player, payload.containerId(),
                    FactoryTerminalSnapshots.machineKey(machine.id()), optional.get(), page);
            PacketDistributor.sendToPlayer(player, new FactoryEditFeedbackS2C(
                    payload.containerId(), true,
                    "已切换为" + (payload.mode() == MachineMode.ACTIVE ? "主动" : "被动") + "模式",
                    null));
        });
    }

    private static void write(RegistryFriendlyByteBuf buf, FactoryMachineModeC2S payload) {
        buf.writeVarInt(payload.containerId());
        buf.writeUUID(payload.machineId());
        buf.writeByte(payload.mode().ordinal());
    }

    private static FactoryMachineModeC2S read(RegistryFriendlyByteBuf buf) {
        int containerId = buf.readVarInt();
        UUID machineId = buf.readUUID();
        int ordinal = buf.readUnsignedByte();
        if (ordinal >= MachineMode.values().length) {
            throw new DecoderException("invalid machine mode: " + ordinal);
        }
        return new FactoryMachineModeC2S(containerId, machineId, MachineMode.values()[ordinal]);
    }
}
