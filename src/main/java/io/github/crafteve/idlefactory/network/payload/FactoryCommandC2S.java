package io.github.crafteve.idlefactory.network.payload;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.command.FactoryCommand;
import io.github.crafteve.idlefactory.network.CommandPort;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：唯一的写命令入口。
 *
 * <p>所有节点、规则、provider 与绑定杖写操作都走这一条包；服务端在 {@link CommandPort}
 * 里做会话校验、执行与快照推送。</p>
 *
 * @param command 命令
 */
public record FactoryCommandC2S(FactoryCommand command) implements CustomPacketPayload {
    /** 包类型：{@code idlefactory:command}。 */
    public static final Type<FactoryCommandC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID, "command"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, FactoryCommandC2S> STREAM_CODEC =
            StreamCodec.of(FactoryCommandC2S::write, FactoryCommandC2S::read);

    @Override
    public Type<FactoryCommandC2S> type() {
        return TYPE;
    }

    /**
     * 服务端处理：交给命令端口。
     *
     * @param payload 负载
     * @param context 上下文
     */
    public static void handle(FactoryCommandC2S payload, IPayloadContext context) {
        context.enqueueWork(() -> CommandPort.handle(context, payload.command()));
    }

    private static void write(RegistryFriendlyByteBuf buf, FactoryCommandC2S payload) {
        FactoryCommand.STREAM_CODEC.encode(buf, payload.command());
    }

    private static FactoryCommandC2S read(RegistryFriendlyByteBuf buf) {
        return new FactoryCommandC2S(FactoryCommand.STREAM_CODEC.decode(buf));
    }
}
