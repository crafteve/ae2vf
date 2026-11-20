package io.github.crafteve.idlefactory.network.payload;

import appeng.api.config.SortDir;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.client.network.ClientTerminalSortHandler;
import io.github.crafteve.idlefactory.menu.TerminalSortMode;

import io.netty.handler.codec.DecoderException;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端确认终端排序偏好。
 *
 * @param containerId 菜单容器 id
 * @param mode        服务端已保存的档位
 * @param direction   服务端已保存的方向
 */
public record FactoryTerminalSortS2C(int containerId, TerminalSortMode mode, SortDir direction)
        implements CustomPacketPayload {
    /** 包类型：{@code idlefactory:terminal_sort_sync}。 */
    public static final Type<FactoryTerminalSortS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID,
                    "terminal_sort_sync"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, FactoryTerminalSortS2C> STREAM_CODEC =
            StreamCodec.of(FactoryTerminalSortS2C::write, FactoryTerminalSortS2C::read);

    @Override
    public Type<FactoryTerminalSortS2C> type() {
        return TYPE;
    }

    /**
     * 客户端处理：只更新当前打开且容器 id 匹配的屏幕。
     *
     * @param payload 负载
     * @param context 上下文
     */
    public static void handle(FactoryTerminalSortS2C payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientTerminalSortHandler.handle(payload));
    }

    private static void write(RegistryFriendlyByteBuf buf, FactoryTerminalSortS2C payload) {
        buf.writeVarInt(payload.containerId());
        buf.writeByte(payload.mode().ordinal());
        buf.writeByte(payload.direction().ordinal());
    }

    private static FactoryTerminalSortS2C read(RegistryFriendlyByteBuf buf) {
        int containerId = buf.readVarInt();
        TerminalSortMode[] modes = TerminalSortMode.values();
        int modeOrdinal = buf.readUnsignedByte();
        if (modeOrdinal >= modes.length) {
            throw new DecoderException("invalid terminal sort mode ordinal: " + modeOrdinal);
        }
        SortDir[] directions = SortDir.values();
        int directionOrdinal = buf.readUnsignedByte();
        if (directionOrdinal >= directions.length) {
            throw new DecoderException("invalid terminal sort direction ordinal: "
                    + directionOrdinal);
        }
        return new FactoryTerminalSortS2C(containerId, modes[modeOrdinal],
                directions[directionOrdinal]);
    }
}
