package io.github.crafteve.ae2vf.network.payload;

import io.github.crafteve.ae2vf.network.codec.ViewTableCodec;

import io.github.crafteve.ae2vf.IdleFactory;
import io.github.crafteve.ae2vf.view.ViewTableDiff;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端的视图表差量。
 *
 * <p>{@code baseRevision} 是客户端应当已持有的版本；对不上就说明客户端错过了中间某版，
 * 由客户端丢弃本包并请求全量——服务端不在这里补历史。</p>
 *
 * @param containerId 菜单容器 id
 * @param baseRevision 差异基于的版本
 * @param revision     应用后的版本
 * @param diff         差异内容
 */
public record ViewTableDiffS2C(int containerId, long baseRevision, long revision,
        ViewTableDiff diff) implements CustomPacketPayload {

    /** 包类型：{@code idlefactory:view_table_diff}。 */
    public static final Type<ViewTableDiffS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID,
                    "view_table_diff"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, ViewTableDiffS2C> STREAM_CODEC =
            StreamCodec.of(ViewTableDiffS2C::write, ViewTableDiffS2C::read);

    @Override
    public Type<ViewTableDiffS2C> type() {
        return TYPE;
    }

    private static void write(RegistryFriendlyByteBuf buf, ViewTableDiffS2C payload) {
        buf.writeVarInt(payload.containerId());
        buf.writeVarLong(payload.baseRevision());
        buf.writeVarLong(payload.revision());
        ViewTableCodec.writeDiff(buf, payload.diff());
    }

    private static ViewTableDiffS2C read(RegistryFriendlyByteBuf buf) {
        int containerId = buf.readVarInt();
        long baseRevision = buf.readVarLong();
        long revision = buf.readVarLong();
        return new ViewTableDiffS2C(containerId, baseRevision, revision,
                ViewTableCodec.readDiff(buf));
    }

    /**
     * 客户端处理：转发给当前屏幕的视图表投递口。
     *
     * @param payload 负载
     * @param context 上下文
     */
    public static void handle(ViewTableDiffS2C payload, IPayloadContext context) {
        io.github.crafteve.ae2vf.client.network.ClientViewTableHandler.accept(payload);
    }
}
