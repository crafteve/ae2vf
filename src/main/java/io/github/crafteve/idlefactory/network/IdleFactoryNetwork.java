package io.github.crafteve.idlefactory.network;

import io.github.crafteve.idlefactory.config.IdleFactoryConfig;
import io.github.crafteve.idlefactory.IdleFactory;

import io.github.crafteve.idlefactory.network.payload.WandLinksRequestC2S;

import io.github.crafteve.idlefactory.network.payload.WandLinksS2C;

import io.github.crafteve.idlefactory.network.payload.FactoryCommandC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryCommandS2C;

import io.github.crafteve.idlefactory.network.payload.FactoryPassiveIoViewC2S;

import io.github.crafteve.idlefactory.network.payload.FactoryPassiveIoViewS2C;

import io.github.crafteve.idlefactory.network.payload.FactoryTerminalSortC2S;

import io.github.crafteve.idlefactory.network.payload.FactoryTerminalSortS2C;

import io.github.crafteve.idlefactory.network.payload.HudNoticeS2C;
import io.github.crafteve.idlefactory.network.payload.ViewTableDiffS2C;

import io.github.crafteve.idlefactory.network.payload.ViewTableRequestC2S;

import io.github.crafteve.idlefactory.network.payload.ViewTableS2C;

import io.github.crafteve.idlefactory.network.payload.wand.WandChainC2S;

import io.github.crafteve.idlefactory.network.payload.wand.WandChainS2C;

import io.github.crafteve.idlefactory.network.payload.wand.WandOpenC2S;

import io.github.crafteve.idlefactory.network.payload.wand.WandOpenS2C;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 网络通道。
 *
 * <p>读侧只有一条通道：视图表（请求、全量页、差量）。另有写命令、被动 IO 视图偏好、排序偏好、
 * 绑定杖链接快照与三层绑定杖包。改包结构时必须递增 {@link #PROTOCOL_VERSION}；
 * 首个公开测试版从 1 开始编号，此前的内部编号（29–42）只留在 ADR 里。</p>
 */
public final class IdleFactoryNetwork {
    /** 网络协议版本：双方不一致时 NeoForge 会拒绝连接，改包结构时记得递增。 */
    public static final String PROTOCOL_VERSION = "1";

    /**
     * 线协议固定的条目硬上限（每个包）。
     *
     * <p>它不是玩法上限：解码时用 {@code min(有效配置值, HARD_WIRE_ENTRIES)}，防止恶意
     * 超大包把服务端内存打爆。玩法上限在 {@code IdleFactoryConfig}。</p>
     */
    public static final int HARD_WIRE_ENTRIES = 32768;

    /** 由 {@link RegisterPayloadHandlersEvent} 创建，事件回调之后才非空。 */
    private static PayloadRegistrar registrar;

    private IdleFactoryNetwork() {
        // 纯静态工具类
    }

    /**
     * 把网络注册挂到 mod 事件总线上。
     *
     * @param modEventBus mod 事件总线
     */
    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(IdleFactoryNetwork::onRegisterPayloadHandlers);
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

        // 写命令：节点、规则、provider 与应用内一切屏幕写操作都走这一对包
        registrar.playToServer(FactoryCommandC2S.TYPE, FactoryCommandC2S.STREAM_CODEC,
                FactoryCommandC2S::handle);
        registrar.playToClient(FactoryCommandS2C.TYPE, FactoryCommandS2C.STREAM_CODEC,
                FactoryCommandS2C::handle);

        // 被动 IO：列表 / 卡片视图写物品组件，服务端确认后回同步
        registrar.playToServer(FactoryPassiveIoViewC2S.TYPE,
                FactoryPassiveIoViewC2S.STREAM_CODEC, FactoryPassiveIoViewC2S::handle);
        registrar.playToClient(FactoryPassiveIoViewS2C.TYPE,
                FactoryPassiveIoViewS2C.STREAM_CODEC, FactoryPassiveIoViewS2C::handle);

        // 两个终端共用的排序偏好：档位 + 方向，服务端写物品组件后回同步
        registrar.playToServer(FactoryTerminalSortC2S.TYPE,
                FactoryTerminalSortC2S.STREAM_CODEC, FactoryTerminalSortC2S::handle);
        registrar.playToClient(FactoryTerminalSortS2C.TYPE,
                FactoryTerminalSortS2C.STREAM_CODEC, FactoryTerminalSortS2C::handle);

        // 绑定杖：链接快照（仓库绑定已并入统一写命令）
        registrar.playToServer(WandLinksRequestC2S.TYPE, WandLinksRequestC2S.STREAM_CODEC,
                WandLinksRequestC2S::handle);
        registrar.playToClient(WandLinksS2C.TYPE, WandLinksS2C.STREAM_CODEC,
                WandLinksS2C::handle);

        // 视图表：请求、全量分页、差量（读侧唯一通道，第 3c 步后旧两对包删除）
        registrar.playToServer(ViewTableRequestC2S.TYPE, ViewTableRequestC2S.STREAM_CODEC,
                ViewTableRequestC2S::handle);
        registrar.playToClient(ViewTableS2C.TYPE, ViewTableS2C.STREAM_CODEC,
                ViewTableS2C::handle);
        registrar.playToClient(ViewTableDiffS2C.TYPE, ViewTableDiffS2C.STREAM_CODEC,
                ViewTableDiffS2C::handle);

        // HUD 提示：失联 / 恢复这类没有界面的通知，统一走模组自己的 HUD 条
        registrar.playToClient(HudNoticeS2C.TYPE, HudNoticeS2C.STREAM_CODEC, HudNoticeS2C::handle);

        // 机器绑定杖：1:1 toggle、连锁、草稿预校验与提交
        registrar.playToServer(WandChainC2S.TYPE, WandChainC2S.STREAM_CODEC, WandChainC2S::handle);
        registrar.playToClient(WandChainS2C.TYPE, WandChainS2C.STREAM_CODEC, WandChainS2C::handle);
        registrar.playToServer(WandOpenC2S.TYPE, WandOpenC2S.STREAM_CODEC, WandOpenC2S::handle);
        registrar.playToClient(WandOpenS2C.TYPE, WandOpenS2C.STREAM_CODEC, WandOpenS2C::handle);

        IdleFactory.LOGGER.debug("Idle Factory payload channel registered (version {})", PROTOCOL_VERSION);
    }
}
