package io.github.crafteve.idlefactory.network.payload;

import java.util.ArrayList;
import java.util.List;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.client.network.ClientFactoryTableHandler;
import appeng.api.stacks.AEKey;
import io.github.crafteve.idlefactory.network.ResourceKeyCodec;

import io.netty.handler.codec.DecoderException;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端的仓库摘要。
 *
 * <p>只带资源 key 与数量；名字、图标由客户端解析。物品 key 自带完整组件，因此同名的不同
 * 变体在界面上是各自独立的格子。</p>
 *
 * <p>组件可能很大，服务端会按字节预算把一份快照拆成多个包：{@code part} 从 0 开始，
 * {@code last} 标记最后一段；客户端按顺序拼完才替换仓库视图。</p>
 *
 * @param containerId 菜单容器 id
 * @param online      仓库是否在线
 * @param truncated   是否因为 1024 条上限被截断
 * @param part        分片序号，从 0 开始
 * @param last        是否最后一个分片
 * @param entries     资源条目，按数量降序
 */
public record FactoryStorageS2C(int containerId, boolean online, boolean truncated,
        int part, boolean last, List<Entry> entries) implements CustomPacketPayload {

    private static final int MAX_ENTRIES = 1024;

    /** 包类型：{@code idlefactory:factory_storage}。 */
    public static final Type<FactoryStorageS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID,
                    "factory_storage"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, FactoryStorageS2C> STREAM_CODEC =
            StreamCodec.of(FactoryStorageS2C::write, FactoryStorageS2C::read);

    public FactoryStorageS2C {
        entries = List.copyOf(entries);
    }

    /** 没有工厂或仓库离线时的空表。 */
    public static FactoryStorageS2C empty(int containerId) {
        return new FactoryStorageS2C(containerId, false, false, 0, true, List.of());
    }

    @Override
    public Type<FactoryStorageS2C> type() {
        return TYPE;
    }

    /**
     * 客户端处理。
     *
     * @param payload 负载
     * @param context 上下文
     */
    public static void handle(FactoryStorageS2C payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientFactoryTableHandler.handleStorage(payload));
    }

    private static void write(RegistryFriendlyByteBuf buf, FactoryStorageS2C payload) {
        buf.writeVarInt(payload.containerId());
        buf.writeBoolean(payload.online());
        buf.writeBoolean(payload.truncated());
        buf.writeVarInt(payload.part());
        buf.writeBoolean(payload.last());
        buf.writeVarInt(payload.entries().size());
        for (Entry entry : payload.entries()) {
            ResourceKeyCodec.write(buf, entry.key());
            buf.writeLong(entry.count());
        }
    }

    private static FactoryStorageS2C read(RegistryFriendlyByteBuf buf) {
        int containerId = buf.readVarInt();
        boolean online = buf.readBoolean();
        boolean truncated = buf.readBoolean();
        int part = buf.readVarInt();
        if (part < 0) {
            throw new DecoderException("invalid storage part: " + part);
        }
        boolean last = buf.readBoolean();
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new DecoderException("invalid storage entry count: " + count);
        }
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            AEKey key = ResourceKeyCodec.read(buf);
            long amount = buf.readLong();
            if (amount < 0L) {
                throw new DecoderException("negative storage count: " + amount);
            }
            entries.add(new Entry(key, amount));
        }
        return new FactoryStorageS2C(containerId, online, truncated, part, last, entries);
    }

    /**
     * 一条仓库资源。
     *
     * @param key   资源
     * @param count 数量
     */
    public record Entry(AEKey key, long count) {
    }
}
