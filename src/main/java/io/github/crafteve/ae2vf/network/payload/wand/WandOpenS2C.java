package io.github.crafteve.ae2vf.network.payload.wand;

import io.github.crafteve.ae2vf.IdleFactory;
import io.github.crafteve.ae2vf.client.wand.WandSubmitter;
import io.github.crafteve.ae2vf.command.CommandReason;
import io.github.crafteve.ae2vf.command.CommandResult;
import io.github.crafteve.ae2vf.key.BlockKey;

import io.netty.handler.codec.DecoderException;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：草稿预校验结果。
 *
 * <p>{@code result.reason()} 是草稿级原因：{@code OK} 或逐方块问题时面板照样打开，只标红
 * {@code result.blocks()}；只有草稿级失败（原因非 OK 且没有冲突方块）才不打开。</p>
 *
 * @param result 预校验结果
 */
public record WandOpenS2C(CommandResult result) implements CustomPacketPayload {
    /** 包类型：{@code idlefactory:wand_open_result}。 */
    public static final Type<WandOpenS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID,
                    "wand_open_result"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, WandOpenS2C> STREAM_CODEC =
            StreamCodec.of(WandOpenS2C::write, WandOpenS2C::read);

    @Override
    public Type<WandOpenS2C> type() {
        return TYPE;
    }

    /**
     * 客户端处理：缓存冲突方块，随后原版打开菜单事件会构造面板。
     *
     * @param payload 回执
     * @param context 上下文
     */
    public static void handle(WandOpenS2C payload, IPayloadContext context) {
        context.enqueueWork(() -> WandSubmitter.handleOpen(payload.result()));
    }

    private static void write(RegistryFriendlyByteBuf buf, WandOpenS2C payload) {
        CommandResult result = payload.result();
        buf.writeByte(result.reason().ordinal());
        buf.writeVarInt(result.blocks().size());
        for (BlockKey block : result.blocks()) {
            buf.writeResourceLocation(block.dimension().location());
            buf.writeLong(block.pos().asLong());
        }
    }

    private static WandOpenS2C read(RegistryFriendlyByteBuf buf) {
        int reasonOrdinal = buf.readUnsignedByte();
        if (reasonOrdinal >= CommandReason.values().length) {
            throw new DecoderException("invalid reason ordinal: " + reasonOrdinal);
        }
        CommandReason reason = CommandReason.values()[reasonOrdinal];
        int count = buf.readVarInt();
        if (count < 0 || count > 4096) {
            throw new DecoderException("方块数量越界：" + count);
        }
        java.util.List<BlockKey> blocks = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ResourceLocation dimension = buf.readResourceLocation();
            blocks.add(BlockKey.of(
                    net.minecraft.resources.ResourceKey.create(
                            net.minecraft.core.registries.Registries.DIMENSION, dimension),
                    net.minecraft.core.BlockPos.of(buf.readLong())));
        }
        return new WandOpenS2C(new CommandResult(reason, null, null, blocks));
    }
}
