package io.github.crafteve.idlefactory.network.payload;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.view.ViewTable;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端的视图表全量页。
 *
 * <p>分页单位是完整的 {@link ViewNode}，不是字节切片：一页里的节点永远完整，客户端按
 * {@code pageIndex} 拼装成同一 revision 的表。单节点超过预算时允许它独占一个超大页——否则
 * 一个节点会把分页逻辑拖成"半个节点"，客户端反而要处理跨页拼节点。</p>
 *
 * <p>每页都带表头，客户端不必依赖页序先拿到 {@code pageIndex = 0} 才知道仓库在不在线。</p>
 *
 * @param containerId 菜单容器 id
 * @param revision    本页属于哪一版
 * @param pageIndex   页序号，从 0 开始
 * @param pageCount   总页数，至少 1
 * @param page        本页的节点（表头相同）
 */
public record ViewTableS2C(int containerId, long revision, int pageIndex, int pageCount,
        ViewTable page) implements CustomPacketPayload {

    /** 包类型：{@code idlefactory:view_table}。 */
    public static final Type<ViewTableS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID, "view_table"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, ViewTableS2C> STREAM_CODEC =
            StreamCodec.of(ViewTableS2C::write, ViewTableS2C::read);

    @Override
    public Type<ViewTableS2C> type() {
        return TYPE;
    }

    private static void write(RegistryFriendlyByteBuf buf, ViewTableS2C payload) {
        buf.writeVarInt(payload.containerId());
        buf.writeVarLong(payload.revision());
        buf.writeVarInt(payload.pageIndex());
        buf.writeVarInt(payload.pageCount());
        ViewTableCodec.writeTable(buf, payload.page());
    }

    private static ViewTableS2C read(RegistryFriendlyByteBuf buf) {
        int containerId = buf.readVarInt();
        long revision = buf.readVarLong();
        int pageIndex = buf.readVarInt();
        int pageCount = buf.readVarInt();
        ViewTable page = ViewTableCodec.readTable(buf);
        if (pageIndex < 0 || pageCount < 1 || pageIndex >= pageCount) {
            throw new io.netty.handler.codec.DecoderException(
                    "invalid view table page " + pageIndex + "/" + pageCount);
        }
        return new ViewTableS2C(containerId, revision, pageIndex, pageCount, page);
    }

    /**
     * 客户端处理：转发给当前屏幕的视图表投递口。
     *
     * @param payload 负载
     * @param context 上下文
     */
    public static void handle(ViewTableS2C payload, IPayloadContext context) {
        io.github.crafteve.idlefactory.client.network.ClientViewTableHandler.accept(payload);
    }
}
