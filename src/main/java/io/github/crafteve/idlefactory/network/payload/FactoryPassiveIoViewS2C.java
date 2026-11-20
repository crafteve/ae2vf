package io.github.crafteve.idlefactory.network.payload;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.client.network.ClientPassiveIoViewHandler;
import io.github.crafteve.idlefactory.menu.PassiveIoViewMode;

import io.netty.handler.codec.DecoderException;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端确认被动 IO 的视图设置。
 *
 * @param containerId 菜单容器 id
 * @param view        服务端已保存的视图
 */
public record FactoryPassiveIoViewS2C(int containerId, PassiveIoViewMode view)
        implements CustomPacketPayload {
    /** 包类型：{@code idlefactory:passive_io_view_sync}。 */
    public static final Type<FactoryPassiveIoViewS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID,
                    "passive_io_view_sync"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, FactoryPassiveIoViewS2C> STREAM_CODEC =
            StreamCodec.of(FactoryPassiveIoViewS2C::write, FactoryPassiveIoViewS2C::read);

    @Override
    public Type<FactoryPassiveIoViewS2C> type() {
        return TYPE;
    }

    /**
     * 客户端处理：只更新当前打开且容器 id 匹配的屏幕。
     *
     * @param payload 负载
     * @param context 上下文
     */
    public static void handle(FactoryPassiveIoViewS2C payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPassiveIoViewHandler.handle(payload));
    }

    private static void write(RegistryFriendlyByteBuf buf, FactoryPassiveIoViewS2C payload) {
        buf.writeVarInt(payload.containerId());
        buf.writeByte(payload.view().ordinal());
    }

    private static FactoryPassiveIoViewS2C read(RegistryFriendlyByteBuf buf) {
        int containerId = buf.readVarInt();
        int ordinal = buf.readUnsignedByte();
        PassiveIoViewMode[] values = PassiveIoViewMode.values();
        if (ordinal >= values.length) {
            throw new DecoderException("invalid passive IO view ordinal: " + ordinal);
        }
        return new FactoryPassiveIoViewS2C(containerId, values[ordinal]);
    }
}
