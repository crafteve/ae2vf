package io.github.crafteve.idlefactory.network;

import io.github.crafteve.idlefactory.IdleFactory;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 网络通道。
 *
 * <p>P0 只注册占位 {@link PingPayload}（S2C）：它没有业务含义，只用来证明通道能通。
 * 真正的工厂快照与意图包在 P1 加，届时记得递增 {@link #PROTOCOL_VERSION}。</p>
 */
public final class ModNetwork {
    /** 网络协议版本：双方不一致时 NeoForge 会拒绝连接，改包结构时记得递增。 */
    public static final String PROTOCOL_VERSION = "3";

    /** 由 {@link RegisterPayloadHandlersEvent} 创建，事件回调之后才非空。 */
    private static PayloadRegistrar registrar;

    private ModNetwork() {
        // 纯静态工具类
    }

    /**
     * 把网络注册挂到 mod 事件总线上。
     *
     * @param modEventBus mod 事件总线
     */
    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(ModNetwork::onRegisterPayloadHandlers);
    }

    /** 当前注册器；仅用于调试与后续扩展，注册阶段之外可能为 null。 */
    public static PayloadRegistrar registrar() {
        return registrar;
    }

    private static void onRegisterPayloadHandlers(RegisterPayloadHandlersEvent event) {
        registrar = event.registrar(PROTOCOL_VERSION);

        // P0：通道自检（无业务含义）
        registrar.playToClient(PingPayload.TYPE, PingPayload.STREAM_CODEC, PingPayload::handle);

        IdleFactory.LOGGER.debug("Idle Factory payload channel registered (version {})", PROTOCOL_VERSION);
    }

    /**
     * 占位负载：没有字段，编码后只带一个包 id。
     *
     * <p>留着它做通道自检；真正的业务负载在 {@code network.payload} 包里。</p>
     */
    public record PingPayload() implements CustomPacketPayload {
        /** 包类型 id：{@code idlefactory:ping}。 */
        public static final Type<PingPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID, "ping"));

        /** 空负载的编解码器：编码不写任何字节，解码直接返回单例。 */
        public static final StreamCodec<RegistryFriendlyByteBuf, PingPayload> STREAM_CODEC =
                StreamCodec.unit(new PingPayload());

        @Override
        public Type<PingPayload> type() {
            return TYPE;
        }

        /**
         * 客户端处理器。不做任何事，只证明包能被收下。
         *
         * @param payload 负载
         * @param context 处理上下文
         */
        public static void handle(PingPayload payload, IPayloadContext context) {
            // 占位：通道验证通过即可
        }
    }
}
