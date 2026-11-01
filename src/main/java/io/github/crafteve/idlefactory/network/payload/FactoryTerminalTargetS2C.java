package io.github.crafteve.idlefactory.network.payload;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.client.network.ClientFactoryTerminalHandler;
import io.github.crafteve.idlefactory.menu.FactoryTerminalTargetKind;

import io.netty.handler.codec.DecoderException;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端的单个目标详情：点击目标后左面板刷新用。
 *
 * <p>只带模式、在线状态与槽数，样板内容仍由右半边 AE2 终端读取；AE2 原生样板容器没有
 * 主动 / 被动概念，模式传 {@code -1}。</p>
 *
 * @param containerId 菜单容器 id
 * @param kind        来源类型
 * @param key         目标稳定键
 * @param name        显示名
 * @param modeOrdinal 机器模式序号；AE2 目标为 {@code -1}
 * @param online      目标是否在线
 * @param capacity    样板槽数
 * @param used        已放样板数
 */
public record FactoryTerminalTargetS2C(int containerId, FactoryTerminalTargetKind kind, String key,
        String name, int modeOrdinal, boolean online, int capacity,
        int used) implements CustomPacketPayload {
    /** 名字 / 键长度上限，与列表包一致。 */
    public static final int MAX_TEXT = 128;

    /** 包类型：{@code idlefactory:terminal_target}。 */
    public static final Type<FactoryTerminalTargetS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID, "terminal_target"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, FactoryTerminalTargetS2C> STREAM_CODEC =
            StreamCodec.of(FactoryTerminalTargetS2C::write, FactoryTerminalTargetS2C::read);

    @Override
    public Type<FactoryTerminalTargetS2C> type() {
        return TYPE;
    }

    /**
     * 客户端处理。
     *
     * @param payload 负载
     * @param context 上下文
     */
    public static void handle(FactoryTerminalTargetS2C payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientFactoryTerminalHandler.handleTarget(payload));
    }

    private static void write(RegistryFriendlyByteBuf buf, FactoryTerminalTargetS2C payload) {
        buf.writeVarInt(payload.containerId());
        buf.writeByte(payload.kind().ordinal());
        buf.writeUtf(payload.key(), MAX_TEXT);
        buf.writeUtf(payload.name(), MAX_TEXT);
        buf.writeByte(payload.modeOrdinal());
        buf.writeBoolean(payload.online());
        buf.writeVarInt(payload.capacity());
        buf.writeVarInt(payload.used());
    }

    private static FactoryTerminalTargetS2C read(RegistryFriendlyByteBuf buf) {
        int containerId = buf.readVarInt();
        FactoryTerminalTargetKind kind = FactoryTerminalTargetKind.read(buf.readUnsignedByte());
        String key = buf.readUtf(MAX_TEXT);
        String name = buf.readUtf(MAX_TEXT);
        int modeOrdinal = buf.readByte();
        if (modeOrdinal < -1) {
            throw new DecoderException("invalid machine mode: " + modeOrdinal);
        }
        boolean online = buf.readBoolean();
        int capacity = buf.readVarInt();
        int used = buf.readVarInt();
        return new FactoryTerminalTargetS2C(containerId, kind, key, name, modeOrdinal, online,
                capacity, used);
    }
}
