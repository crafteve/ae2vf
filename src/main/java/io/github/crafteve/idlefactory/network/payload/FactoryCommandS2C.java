package io.github.crafteve.idlefactory.network.payload;

import java.util.ArrayList;
import java.util.List;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.client.network.ClientCommandHandler;
import io.github.crafteve.idlefactory.command.CommandKind;
import io.github.crafteve.idlefactory.command.CommandReason;
import io.github.crafteve.idlefactory.command.CommandResult;
import io.github.crafteve.idlefactory.location.BlockKey;

import io.netty.handler.codec.DecoderException;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：写命令回执。
 *
 * <p>只回原因、不回文本；方块列表按 {@code CommandResult} 的约定解释：{@code block == null}
 * 是命令级结果，{@code block != null} 是逐方块结果。</p>
 *
 * @param containerId 菜单容器 id
 * @param kind        命令种类
 * @param requestSeq  请求序号；草稿提交用它对上回执，其它命令为 0
 * @param result      回执
 */
public record FactoryCommandS2C(int containerId, CommandKind kind, long requestSeq,
        CommandResult result)
        implements CustomPacketPayload {
    /** 包类型：{@code idlefactory:command_result}。 */
    public static final Type<FactoryCommandS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID, "command_result"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, FactoryCommandS2C> STREAM_CODEC =
            StreamCodec.of(FactoryCommandS2C::write, FactoryCommandS2C::read);

    @Override
    public Type<FactoryCommandS2C> type() {
        return TYPE;
    }

    /**
     * 客户端处理：交给回执分发器。
     *
     * @param payload 负载
     * @param context 上下文
     */
    public static void handle(FactoryCommandS2C payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientCommandHandler.handle(payload));
    }

    private static void write(RegistryFriendlyByteBuf buf, FactoryCommandS2C payload) {
        buf.writeVarInt(payload.containerId());
        buf.writeByte(payload.kind().ordinal());
        buf.writeVarLong(payload.requestSeq());
        CommandResult result = payload.result();
        buf.writeByte(result.reason().ordinal());
        buf.writeBoolean(result.nodeId() != null);
        if (result.nodeId() != null) {
            buf.writeUUID(result.nodeId());
        }
        buf.writeBoolean(result.block() != null);
        if (result.block() != null) {
            writeBlock(buf, result.block());
        }
        buf.writeVarInt(result.blocks().size());
        for (BlockKey block : result.blocks()) {
            writeBlock(buf, block);
        }
    }

    private static FactoryCommandS2C read(RegistryFriendlyByteBuf buf) {
        int containerId = buf.readVarInt();
        CommandKind kind = readEnum(buf, CommandKind.values(), "command kind");
        long requestSeq = buf.readVarLong();
        CommandReason reason = readEnum(buf, CommandReason.values(), "command reason");
        java.util.UUID nodeId = buf.readBoolean() ? buf.readUUID() : null;
        BlockKey block = buf.readBoolean() ? readBlock(buf) : null;
        int count = buf.readVarInt();
        if (count < 0 || count > 4096) {
            throw new DecoderException("方块数量越界：" + count);
        }
        List<BlockKey> blocks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            blocks.add(readBlock(buf));
        }
        return new FactoryCommandS2C(containerId, kind, requestSeq,
                new CommandResult(reason, nodeId, block, blocks));
    }

    private static void writeBlock(RegistryFriendlyByteBuf buf, BlockKey block) {
        buf.writeResourceLocation(block.dimension().location());
        buf.writeLong(block.pos().asLong());
    }

    private static BlockKey readBlock(RegistryFriendlyByteBuf buf) {
        ResourceLocation dimension = buf.readResourceLocation();
        return BlockKey.of(
                net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION, dimension),
                net.minecraft.core.BlockPos.of(buf.readLong()));
    }

    private static <E extends Enum<E>> E readEnum(RegistryFriendlyByteBuf buf, E[] values,
            String name) {
        int ordinal = buf.readUnsignedByte();
        if (ordinal >= values.length) {
            throw new DecoderException("invalid " + name + " ordinal: " + ordinal);
        }
        return values[ordinal];
    }
}
