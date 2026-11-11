package io.github.crafteve.idlefactory.network;

import io.github.crafteve.idlefactory.IdleFactory;
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
import io.github.crafteve.idlefactory.network.payload.wand.WandChainC2S;
import io.github.crafteve.idlefactory.network.payload.wand.WandChainS2C;
import io.github.crafteve.idlefactory.network.payload.wand.WandCommitC2S;
import io.github.crafteve.idlefactory.network.payload.wand.WandCommitS2C;
import io.github.crafteve.idlefactory.network.payload.wand.WandOpenC2S;
import io.github.crafteve.idlefactory.network.payload.wand.WandOpenS2C;
import io.github.crafteve.idlefactory.network.payload.wand.WandToggleC2S;
import io.github.crafteve.idlefactory.network.payload.wand.WandToggleS2C;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 网络通道。
 *
 * <p>当前包含机器表、仓库快照、IO 规则动作、编辑反馈、界面打开请求、被动 IO 视图同步、
 * 绑定杖操作与三层链接快照。绑定杖的 1:1 toggle、连锁、草稿预校验与提交各有独立的请求 /
 * 回执包，不再复用仓库动作包。改包结构时记得递增 {@link #PROTOCOL_VERSION}。</p>
 */
public final class ModNetwork {
    /** 网络协议版本：双方不一致时 NeoForge 会拒绝连接，改包结构时记得递增。 */
    public static final String PROTOCOL_VERSION = "32";

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

        // 绑定杖：仓库动作 + 三层链接快照
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

        // 机器绑定杖：1:1 toggle、连锁、草稿预校验与提交
        registrar.playToServer(WandToggleC2S.TYPE, WandToggleC2S.STREAM_CODEC,
                WandToggleC2S::handle);
        registrar.playToClient(WandToggleS2C.TYPE, WandToggleS2C.STREAM_CODEC,
                WandToggleS2C::handle);
        registrar.playToServer(WandChainC2S.TYPE, WandChainC2S.STREAM_CODEC, WandChainC2S::handle);
        registrar.playToClient(WandChainS2C.TYPE, WandChainS2C.STREAM_CODEC, WandChainS2C::handle);
        registrar.playToServer(WandOpenC2S.TYPE, WandOpenC2S.STREAM_CODEC, WandOpenC2S::handle);
        registrar.playToClient(WandOpenS2C.TYPE, WandOpenS2C.STREAM_CODEC, WandOpenS2C::handle);
        registrar.playToServer(WandCommitC2S.TYPE, WandCommitC2S.STREAM_CODEC,
                WandCommitC2S::handle);
        registrar.playToClient(WandCommitS2C.TYPE, WandCommitS2C.STREAM_CODEC,
                WandCommitS2C::handle);

        IdleFactory.LOGGER.debug("Idle Factory payload channel registered (version {})", PROTOCOL_VERSION);
    }
}