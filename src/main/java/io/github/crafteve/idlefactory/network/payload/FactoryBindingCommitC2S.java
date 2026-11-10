package io.github.crafteve.idlefactory.network.payload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.BindingDraft;
import io.github.crafteve.idlefactory.factory.BindingMode;
import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryRuntime;
import io.github.crafteve.idlefactory.factory.FactoryService;
import io.github.crafteve.idlefactory.factory.location.BlockKey;
import io.github.crafteve.idlefactory.menu.FactoryNodeMenu;
import io.github.crafteve.idlefactory.network.ModNetwork;

import io.netty.handler.codec.DecoderException;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.minecraft.world.level.Level;

/**
 * 客户端 → 服务端的绑定杖草稿提交。
 *
 * <p>客户端只发完整草稿（模式、目标节点、机器 / 方块）；服务端不读物品组件，按玩家工厂与
 * {@code LinkGate} 逐块重查后再原子应用。</p>
 *
 * @param containerId 菜单容器 id
 * @param draft       完整草稿
 */
public record FactoryBindingCommitC2S(int containerId, BindingDraft draft)
        implements CustomPacketPayload {

    /** 包类型：{@code idlefactory:binding_commit}。 */
    public static final Type<FactoryBindingCommitC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID, "binding_commit"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, FactoryBindingCommitC2S> STREAM_CODEC =
            StreamCodec.of(FactoryBindingCommitC2S::write, FactoryBindingCommitC2S::read);

    @Override
    public Type<FactoryBindingCommitC2S> type() {
        return TYPE;
    }

    /**
     * 服务端处理：校验菜单与工厂，交给绑定服务做全量校验与原子提交。
     *
     * @param payload 提交包
     * @param context 上下文
     */
    public static void handle(FactoryBindingCommitC2S payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            // 世界里直接提交时 containerId 为 0；界面里提交才校验菜单 id。
            if (payload.containerId() != 0
                    && (!(player.containerMenu instanceof FactoryNodeMenu menu)
                    || menu.containerId != payload.containerId())) {
                return;
            }
            FactoryRuntime runtime = FactoryRuntime.get();
            if (runtime == null) {
                feedback(player, payload.containerId(), false, null, 0, "Idle Factory 尚未启动");
                return;
            }
            var factory = runtime.manager().byOwner(player.getUUID());
            if (factory.isEmpty()) {
                feedback(player, payload.containerId(), false, null, 0, "你还没有工厂");
                return;
            }
            FactoryService.BindingResult result = FactoryService.commitBinding(factory.get(),
                    payload.draft());
            feedback(player, payload.containerId(), result.ok(), result.nodeId(),
                    result.machines(), result.message());
        });
    }

    private static void feedback(ServerPlayer player, int containerId, boolean ok,
            @Nullable UUID nodeId, int machines, String message) {
        PacketDistributor.sendToPlayer(player, new FactoryBindingCommitS2C(containerId, ok,
                nodeId, machines, message));
    }

    private static void write(RegistryFriendlyByteBuf buf, FactoryBindingCommitC2S payload) {
        BindingDraft draft = payload.draft();
        buf.writeVarInt(payload.containerId());
        buf.writeByte(draft.mode().ordinal());
        buf.writeBoolean(draft.nodeId() != null);
        if (draft.nodeId() != null) {
            buf.writeUUID(draft.nodeId());
        }
        buf.writeVarInt(draft.machines().size());
        for (BindingDraft.MachineDraft machine : draft.machines()) {
            ResourceLocation.STREAM_CODEC.encode(buf, machine.block().dimension().location());
            buf.writeLong(machine.block().pos().asLong());
        }
    }

    private static FactoryBindingCommitC2S read(RegistryFriendlyByteBuf buf) {
        int containerId = buf.readVarInt();
        int modeOrdinal = buf.readUnsignedByte();
        if (modeOrdinal >= BindingMode.values().length) {
            throw new DecoderException("invalid binding mode: " + modeOrdinal);
        }
        BindingMode mode = BindingMode.values()[modeOrdinal];
        UUID nodeId = buf.readBoolean() ? buf.readUUID() : null;
        int machineCount = readCount(buf, ModNetwork.wireLimit(32768), "machines");
        List<BindingDraft.MachineDraft> machines = new ArrayList<>(machineCount);
        for (int i = 0; i < machineCount; i++) {
            ResourceLocation dimension = ResourceLocation.STREAM_CODEC.decode(buf);
            machines.add(new BindingDraft.MachineDraft(BlockKey.of(
                    ResourceKey.create(Registries.DIMENSION, dimension),
                    BlockPos.of(buf.readLong()))));
        }
        return new FactoryBindingCommitC2S(containerId, new BindingDraft(mode, nodeId, machines));
    }

    private static int readCount(RegistryFriendlyByteBuf buf, int max, String name) {
        int count = buf.readVarInt();
        if (count < 0 || count > max) {
            throw new DecoderException("invalid " + name + " count: " + count);
        }
        return count;
    }
}
