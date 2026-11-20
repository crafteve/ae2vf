package io.github.crafteve.ae2vf.network.payload;

import io.github.crafteve.ae2vf.VoidFactory;
import io.github.crafteve.ae2vf.menu.passiveio.PassiveIoTerminalMenu;
import io.github.crafteve.ae2vf.menu.PassiveIoViewMode;

import io.netty.handler.codec.DecoderException;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端请求切换被动 IO 的列表 / 卡片视图。
 *
 * <p>视图存在物品的 {@code ae2:exported_settings} 组件里；客户端直接改自己的物品栈不会
 * 同步到服务端，所以必须由服务端写组件，再回一份确认。服务端只接受当前打开的
 * {@link PassiveIoTerminalMenu} 与匹配的容器 id。</p>
 *
 * @param containerId 菜单容器 id
 * @param view        目标视图
 */
public record FactoryPassiveIoViewC2S(int containerId, PassiveIoViewMode view)
        implements CustomPacketPayload {
    /** 包类型：{@code ae2vf:passive_io_view}。 */
    public static final Type<FactoryPassiveIoViewC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(VoidFactory.MODID,
                    "passive_io_view"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, FactoryPassiveIoViewC2S> STREAM_CODEC =
            StreamCodec.of(FactoryPassiveIoViewC2S::write, FactoryPassiveIoViewC2S::read);

    @Override
    public Type<FactoryPassiveIoViewC2S> type() {
        return TYPE;
    }

    /**
     * 服务端处理：校验菜单后写物品组件，并回 S2C 确认。
     *
     * @param payload 请求
     * @param context 上下文
     */
    public static void handle(FactoryPassiveIoViewC2S payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !(player.containerMenu instanceof PassiveIoTerminalMenu menu)
                    || menu.containerId != payload.containerId()) {
                return;
            }
            menu.host().setView(payload.view());
            player.getInventory().setChanged();
            PacketDistributor.sendToPlayer(player,
                    new FactoryPassiveIoViewS2C(payload.containerId(), payload.view()));
        });
    }

    private static void write(RegistryFriendlyByteBuf buf, FactoryPassiveIoViewC2S payload) {
        buf.writeVarInt(payload.containerId());
        buf.writeByte(payload.view().ordinal());
    }

    private static FactoryPassiveIoViewC2S read(RegistryFriendlyByteBuf buf) {
        int containerId = buf.readVarInt();
        int ordinal = buf.readUnsignedByte();
        PassiveIoViewMode[] values = PassiveIoViewMode.values();
        if (ordinal >= values.length) {
            throw new DecoderException("invalid passive IO view ordinal: " + ordinal);
        }
        return new FactoryPassiveIoViewC2S(containerId, values[ordinal]);
    }
}
