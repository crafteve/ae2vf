package io.github.crafteve.idlefactory.network;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.network.payload.FactoryEditFeedbackS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryLinksRequestC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryLinksS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryMachineActionC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryRuleActionC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryStorageActionC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryStorageRequestC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryStorageS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryTableRequestC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryTableS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryWandActionC2S;
import io.github.crafteve.idlefactory.network.payload.OpenFactoryGuiC2S;

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
 * <p>当前包含机器表、仓库快照、仓库动作、机器动作、IO 规则动作、编辑反馈、终端打开界面与
 * 绑定杖链接同步。改包结构时记得递增 {@link #PROTOCOL_VERSION}。</p>
 */
public final class ModNetwork {
    /** 网络协议版本：双方不一致时 NeoForge 会拒绝连接，改包结构时记得递增。 */
    public static final String PROTOCOL_VERSION = "20";

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

        // P0：通道自检（无业务含义），保留
        registrar.playToClient(PingPayload.TYPE, PingPayload.STREAM_CODEC, PingPayload::handle);

        // P1 第一小步：机器表请求 / 回包
        registrar.playToServer(FactoryTableRequestC2S.TYPE, FactoryTableRequestC2S.STREAM_CODEC,
                FactoryTableRequestC2S::handle);
        registrar.playToClient(FactoryTableS2C.TYPE, FactoryTableS2C.STREAM_CODEC,
                FactoryTableS2C::handle);
        registrar.playToServer(FactoryStorageRequestC2S.TYPE,
                FactoryStorageRequestC2S.STREAM_CODEC, FactoryStorageRequestC2S::handle);
        registrar.playToClient(FactoryStorageS2C.TYPE, FactoryStorageS2C.STREAM_CODEC,
                FactoryStorageS2C::handle);
        registrar.playToServer(FactoryStorageActionC2S.TYPE,
                FactoryStorageActionC2S.STREAM_CODEC, FactoryStorageActionC2S::handle);

        // P1：机器 / IO 编辑
        registrar.playToServer(FactoryMachineActionC2S.TYPE, FactoryMachineActionC2S.STREAM_CODEC,
                FactoryMachineActionC2S::handle);
        registrar.playToServer(FactoryRuleActionC2S.TYPE, FactoryRuleActionC2S.STREAM_CODEC,
                FactoryRuleActionC2S::handle);
        registrar.playToClient(FactoryEditFeedbackS2C.TYPE, FactoryEditFeedbackS2C.STREAM_CODEC,
                FactoryEditFeedbackS2C::handle);

        // 物品入口：终端右键 / 快捷键请求打开界面
        registrar.playToServer(OpenFactoryGuiC2S.TYPE, OpenFactoryGuiC2S.STREAM_CODEC,
                OpenFactoryGuiC2S::handle);

        // 绑定杖：操作请求 + 链接快照
        registrar.playToServer(FactoryWandActionC2S.TYPE, FactoryWandActionC2S.STREAM_CODEC,
                FactoryWandActionC2S::handle);
        registrar.playToServer(FactoryLinksRequestC2S.TYPE, FactoryLinksRequestC2S.STREAM_CODEC,
                FactoryLinksRequestC2S::handle);
        registrar.playToClient(FactoryLinksS2C.TYPE, FactoryLinksS2C.STREAM_CODEC,
                FactoryLinksS2C::handle);

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
