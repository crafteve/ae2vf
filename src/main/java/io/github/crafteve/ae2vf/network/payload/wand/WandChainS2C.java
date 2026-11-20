package io.github.crafteve.ae2vf.network.payload.wand;

import io.github.crafteve.ae2vf.VoidFactory;
import io.github.crafteve.ae2vf.client.wand.WandSubmitter;
import io.github.crafteve.ae2vf.command.CommandReason;
import io.github.crafteve.ae2vf.command.WandChainResult;
import io.github.crafteve.ae2vf.command.WandDraftCodec;
import io.github.crafteve.ae2vf.command.CommandChain;
import io.github.crafteve.ae2vf.key.BlockKey;

import io.netty.handler.codec.DecoderException;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：连锁查找到的机器群与原因。
 *
 * @param result 机器列表与原因
 */
public record WandChainS2C(WandChainResult result) implements CustomPacketPayload {
    /** 包类型：{@code ae2vf:wand_chain_result}。 */
    public static final Type<WandChainS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(VoidFactory.MODID,
                    "wand_chain_result"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, WandChainS2C> STREAM_CODEC =
            StreamCodec.of(WandChainS2C::write, WandChainS2C::read);

    @Override
    public Type<WandChainS2C> type() {
        return TYPE;
    }

    /**
     * 客户端处理：把机器批量加入草稿。
     *
     * @param payload 回执
     * @param context 上下文
     */
    public static void handle(WandChainS2C payload, IPayloadContext context) {
        context.enqueueWork(() -> WandSubmitter.handleChain(payload.result()));
    }

    private static void write(RegistryFriendlyByteBuf buf, WandChainS2C payload) {
        WandChainResult result = payload.result();
        buf.writeByte(result.reason().ordinal());
        WandDraftCodec.writeBlockKeys(buf, result.blocks(), CommandChain.MAX_MACHINES);
    }

    private static WandChainS2C read(RegistryFriendlyByteBuf buf) {
        int ordinal = buf.readUnsignedByte();
        if (ordinal >= CommandReason.values().length) {
            throw new DecoderException("invalid reason ordinal: " + ordinal);
        }
        return new WandChainS2C(new WandChainResult(CommandReason.values()[ordinal],
                WandDraftCodec.readBlockKeys(buf, CommandChain.MAX_MACHINES)));
    }
}
