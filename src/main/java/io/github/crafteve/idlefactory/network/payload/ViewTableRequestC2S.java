package io.github.crafteve.idlefactory.network.payload;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.menu.FactoryGuiOpening;
import io.github.crafteve.idlefactory.network.ViewPort;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端同步视图表：告诉服务端自己手里是哪一版。
 *
 * <p>服务端据此决定回全量还是回差量：{@code knownRevision} 与出口当前版本一致就按差量处理
 * （表没变则什么都不发），不一致就发全量页。客户端重开终端时手里的版本一定是 {@code 0}，
 * 所以"关闭后再打开走全量"不需要额外的标志位。</p>
 *
 * @param containerId   菜单容器 id
 * @param knownRevision 客户端已知版本；{@code 0} 表示还没有表
 */
public record ViewTableRequestC2S(int containerId, long knownRevision)
        implements CustomPacketPayload {

    /** 包类型：{@code idlefactory:view_table_request}。 */
    public static final Type<ViewTableRequestC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID,
                    "view_table_request"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, ViewTableRequestC2S> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeVarInt(payload.containerId());
                        buf.writeVarLong(payload.knownRevision());
                    },
                    buf -> new ViewTableRequestC2S(buf.readVarInt(), buf.readVarLong()));

    @Override
    public Type<ViewTableRequestC2S> type() {
        return TYPE;
    }

    /**
     * 服务端处理：校验容器后交给视图出口决定回全量还是差量。
     *
     * @param payload 请求
     * @param context 上下文
     */
    public static void handle(ViewTableRequestC2S payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!FactoryGuiOpening.isFactoryMenu(player.containerMenu, payload.containerId())) {
                return;
            }
            ViewPort.sync(player, payload.containerId(), payload.knownRevision());
        });
    }
}
