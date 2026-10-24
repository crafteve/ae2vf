package io.github.crafteve.idlefactory.network.payload;

import java.util.List;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.client.ClientPacketHandler;

import appeng.api.stacks.AEKey;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端的库存快照。
 *
 * <p>客户端只读：界面永远以服务端发来的这一份为准，不在本地推算库存。</p>
 *
 * @param equipped 玩家是否装备了奇点
 * @param bound    奇点是否成功绑定到 ME 网络
 * @param entries  库存条目（按数量降序，最多 {@link #MAX_ENTRIES} 条，只含物品）
 */
public record StorageSnapshotS2C(boolean equipped, boolean bound, List<Entry> entries)
        implements CustomPacketPayload {
    /** 单次同步的最大条目数，控制包体积。 */
    public static final int MAX_ENTRIES = 200;

    /** 包类型 id：{@code idlefactory:storage_snapshot}。 */
    public static final Type<StorageSnapshotS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID, "storage_snapshot"));

    /** 三个字段依次编码；列表解码上限与抓取上限一致。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, StorageSnapshotS2C> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, StorageSnapshotS2C::equipped,
                    ByteBufCodecs.BOOL, StorageSnapshotS2C::bound,
                    Entry.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_ENTRIES)), StorageSnapshotS2C::entries,
                    StorageSnapshotS2C::new);

    /** 空快照：未装备、未绑定、无库存。 */
    public static StorageSnapshotS2C empty() {
        return new StorageSnapshotS2C(false, false, List.of());
    }

    @Override
    public Type<StorageSnapshotS2C> type() {
        return TYPE;
    }

    /** 客户端处理：交给客户端缓存，再由它推给当前打开的 {@code FactoryScreen}。 */
    public static void handle(StorageSnapshotS2C payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPacketHandler.handleStorageSnapshot(payload));
    }

    /** 一条库存记录。 */
    public record Entry(AEKey key, long amount) {
        /** {@link AEKey#STREAM_CODEC} 需要 {@link RegistryFriendlyByteBuf}，与本包的缓冲区一致。 */
        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC =
                StreamCodec.composite(
                        AEKey.STREAM_CODEC, Entry::key,
                        ByteBufCodecs.VAR_LONG, Entry::amount,
                        Entry::new);
    }
}
