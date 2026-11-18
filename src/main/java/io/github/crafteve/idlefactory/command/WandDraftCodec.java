package io.github.crafteve.idlefactory.command;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import io.github.crafteve.idlefactory.config.IdleFactoryConfig;
import io.github.crafteve.idlefactory.location.BlockKey;
import io.github.crafteve.idlefactory.network.ModNetwork;

import io.netty.handler.codec.DecoderException;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/**
 * 绑定杖草稿与方块位置的线协议编解码。
 *
 * <p>草稿条目上限取「单节点机器有效上限」与线协议硬上限的较小者；解码侧越界直接拒绝整包，
 * 不截断——截断会让服务端按半份草稿写库。</p>
 */
public final class WandDraftCodec {
    /** 草稿编解码。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, WandDraft> DRAFT =
            StreamCodec.of(WandDraftCodec::writeDraft, WandDraftCodec::readDraft);

    private WandDraftCodec() {
        // 纯静态编解码
    }

    /**
     * 草稿条目上限：单节点机器有效上限与线协议硬上限的较小者。
     *
     * @return 加与删各自的数量上限
     */
    public static int draftLimit() {
        return ModNetwork.wireLimit(IdleFactoryConfig.maxMachinesPerNode());
    }

    /**
     * 写一个方块位置。
     *
     * @param buf 缓冲
     * @param key 方块位置
     */
    public static void writeBlockKey(RegistryFriendlyByteBuf buf, BlockKey key) {
        buf.writeResourceLocation(key.dimension().location());
        buf.writeLong(key.pos().asLong());
    }

    /**
     * 读一个方块位置。
     *
     * @param buf 缓冲
     * @return 方块位置
     */
    public static BlockKey readBlockKey(RegistryFriendlyByteBuf buf) {
        ResourceLocation dimension = buf.readResourceLocation();
        BlockPos pos = BlockPos.of(buf.readLong());
        return BlockKey.of(ResourceKey.create(Registries.DIMENSION, dimension), pos);
    }

    /**
     * 写一个方块列表，超出上限的部分截掉。
     *
     * @param buf   缓冲
     * @param keys  方块列表
     * @param limit 线上上限
     */
    public static void writeBlockKeys(RegistryFriendlyByteBuf buf, List<BlockKey> keys, int limit) {
        int count = Math.min(keys.size(), limit);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            writeBlockKey(buf, keys.get(i));
        }
    }

    /**
     * 读一个方块列表，数量越界直接拒绝。
     *
     * @param buf   缓冲
     * @param limit 线上上限
     * @return 方块列表
     */
    public static List<BlockKey> readBlockKeys(RegistryFriendlyByteBuf buf, int limit) {
        int count = readCount(buf, limit, "方块列表");
        List<BlockKey> keys = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            keys.add(readBlockKey(buf));
        }
        return keys;
    }

    private static void writeDraft(RegistryFriendlyByteBuf buf, WandDraft draft) {
        buf.writeBoolean(draft.nodeId() != null);
        if (draft.nodeId() != null) {
            buf.writeUUID(draft.nodeId());
        }
        writeBlockKeys(buf, draft.added(), draftLimit());
        buf.writeVarInt(draft.removed().size());
        for (UUID machineId : draft.removed()) {
            buf.writeUUID(machineId);
        }
        buf.writeVarLong(draft.requestSeq());
    }

    private static WandDraft readDraft(RegistryFriendlyByteBuf buf) {
        UUID nodeId = buf.readBoolean() ? buf.readUUID() : null;
        int limit = draftLimit();
        List<BlockKey> added = readBlockKeys(buf, limit);
        Set<BlockKey> seen = new HashSet<>(added.size());
        for (BlockKey block : added) {
            if (!seen.add(block)) {
                throw new DecoderException("草稿里的方块重复：" + block);
            }
        }
        int removedCount = readCount(buf, limit, "删除机器");
        if (added.size() + removedCount > limit) {
            throw new DecoderException("草稿条目超过上限：" + (added.size() + removedCount));
        }
        Set<UUID> removed = new HashSet<>(removedCount);
        for (int i = 0; i < removedCount; i++) {
            removed.add(buf.readUUID());
        }
        long requestSeq = buf.readVarLong();
        return decode("草稿", () -> new WandDraft(nodeId, added, removed, requestSeq));
    }

    private static int readCount(RegistryFriendlyByteBuf buf, int limit, String what) {
        int count = buf.readVarInt();
        if (count < 0 || count > limit) {
            throw new DecoderException(what + "数量越界：" + count);
        }
        return count;
    }

    private static <T> T decode(String what, Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (IllegalArgumentException e) {
            throw new DecoderException(what + "不合法：" + e.getMessage(), e);
        }
    }
}
