package io.github.crafteve.idlefactory.network;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.network.payload.FactoryBindingCommitC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryBindingCommitS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryEditFeedbackS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryLinksRequestC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryLinksS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryNodeActionC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryNodeProviderC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryNodeRenameC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryNodeTableRequestC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryNodeTableS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryPassiveIoViewC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryPassiveIoViewS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryRuleActionC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryTableRequestC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryTableS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryWandActionC2S;

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
 * <p>当前包含机器表、仓库快照、仓库动作、机器动作、IO 规则动作、编辑反馈、界面打开请求、
 * 被动 IO 视图同步与绑定杖链接同步。第二版终端的视图切换与样板目标包已删除。改包结构时
 * 记得递增 {@link #PROTOCOL_VERSION}。</p>
 */
public final class ModNetwork {
    /** 网络协议版本：双方不一致时 NeoForge 会拒绝连接，改包结构时记得递增。 */
    public static final String PROTOCOL_VERSION = "29";

    /**
     * 线协议固定的条目硬上限（每个包）。
     *
     * <p>它不是玩法上限：解码时用 {@code min(有效配置值, HARD_WIRE_ENTRIES)}，防止恶意
     * 超大包把服务端内存打爆。玩法上限在 {@code FactoryLimitsConfig}。</p>
     */
    public static final int HARD_WIRE_ENTRIES = 32768;

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

    /**
     * 把有效配置上限夹到线协议硬上限。
     *
     * @param effective 有效配置上限
     * @return 解码可用的条目上限
     */
    public static int wireLimit(int effective) {
        return Math.min(effective, HARD_WIRE_ENTRIES);
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

        // P1：IO 编辑
        registrar.playToServer(FactoryRuleActionC2S.TYPE, FactoryRuleActionC2S.STREAM_CODEC,
                FactoryRuleActionC2S::handle);
        registrar.playToClient(FactoryEditFeedbackS2C.TYPE, FactoryEditFeedbackS2C.STREAM_CODEC,
                FactoryEditFeedbackS2C::handle);

        // 被动 IO：列表 / 卡片视图写物品组件，服务端确认后回同步
        registrar.playToServer(FactoryPassiveIoViewC2S.TYPE,
                FactoryPassiveIoViewC2S.STREAM_CODEC, FactoryPassiveIoViewC2S::handle);
        registrar.playToClient(FactoryPassiveIoViewS2C.TYPE,
                FactoryPassiveIoViewS2C.STREAM_CODEC, FactoryPassiveIoViewS2C::handle);

        // 绑定杖：操作请求 + 链接快照
        registrar.playToServer(FactoryWandActionC2S.TYPE, FactoryWandActionC2S.STREAM_CODEC,
                FactoryWandActionC2S::handle);
        registrar.playToServer(FactoryLinksRequestC2S.TYPE, FactoryLinksRequestC2S.STREAM_CODEC,
                FactoryLinksRequestC2S::handle);
        registrar.playToClient(FactoryLinksS2C.TYPE, FactoryLinksS2C.STREAM_CODEC,
                FactoryLinksS2C::handle);

        // 节点管理终端：节点表请求 / 回包 / provider 设置 / 改名
        registrar.playToServer(FactoryNodeTableRequestC2S.TYPE,
                FactoryNodeTableRequestC2S.STREAM_CODEC, FactoryNodeTableRequestC2S::handle);
        registrar.playToClient(FactoryNodeTableS2C.TYPE, FactoryNodeTableS2C.STREAM_CODEC,
                FactoryNodeTableS2C::handle);
        registrar.playToServer(FactoryNodeProviderC2S.TYPE,
                FactoryNodeProviderC2S.STREAM_CODEC, FactoryNodeProviderC2S::handle);
        registrar.playToServer(FactoryNodeRenameC2S.TYPE,
                FactoryNodeRenameC2S.STREAM_CODEC, FactoryNodeRenameC2S::handle);
        registrar.playToServer(FactoryNodeActionC2S.TYPE,
                FactoryNodeActionC2S.STREAM_CODEC, FactoryNodeActionC2S::handle);

        // 绑定杖草稿：一次提交 + 结果
        registrar.playToServer(FactoryBindingCommitC2S.TYPE,
                FactoryBindingCommitC2S.STREAM_CODEC, FactoryBindingCommitC2S::handle);
        registrar.playToClient(FactoryBindingCommitS2C.TYPE,
                FactoryBindingCommitS2C.STREAM_CODEC, FactoryBindingCommitS2C::handle);

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
