package io.github.crafteve.idlefactory.command;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import io.github.crafteve.idlefactory.config.IdleFactoryConfig;
import io.github.crafteve.idlefactory.location.BlockKey;
import io.github.crafteve.idlefactory.factory.wand.WandCommitOutcome;
import io.github.crafteve.idlefactory.factory.wand.WandDeleteReason;
import io.github.crafteve.idlefactory.command.WandDraft;
import io.github.crafteve.idlefactory.factory.wand.WandFailure;
import io.github.crafteve.idlefactory.factory.wand.WandIssue;
import io.github.crafteve.idlefactory.factory.wand.WandLimits;
import io.github.crafteve.idlefactory.network.ModNetwork;

import io.netty.handler.codec.DecoderException;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * 绑定杖线协议的共用编解码：草稿、问题、回执、失败枚举与方块位置。
 *
 * <p>枚举一律 {@code writeByte} + 越界 {@link DecoderException}，不用 {@code readEnum}：
 * 客户端与服务端的枚举序号必须显式受控，新增值追加在末尾就还能读懂旧包。所有数量在解码侧
 * 先夹到上限再分配：草稿条目数是「有效单节点机器上限」与线协议硬上限的较小者，问题条数是
 * {@link WandLimits#MAX_ISSUES_WIRE}；越界直接拒绝整包，不截断。</p>
 *
 * <p>方块位置按「维度注册名 + 坐标 long」编码，与物品组件的方块编解码同构；草稿记录里的
 * 字段顺序与编码顺序一致。</p>
 */
public final class WandDraftCodec {
    /** 草稿编解码。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, WandDraft> DRAFT =
            StreamCodec.of(WandDraftCodec::writeDraft, WandDraftCodec::readDraft);
    /** 单条问题编解码。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, WandIssue> ISSUE =
            StreamCodec.of(WandDraftCodec::writeIssue, WandDraftCodec::readIssue);
    /** 回执编解码。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, WandCommitOutcome> OUTCOME =
            StreamCodec.of(WandDraftCodec::writeOutcome, WandDraftCodec::readOutcome);

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
     * @param buf  缓冲
     * @param key  方块位置
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

    /**
     * 写失败枚举。
     *
     * @param buf     缓冲
     * @param failure 失败原因
     */
    public static void writeFailure(RegistryFriendlyByteBuf buf, WandFailure failure) {
        buf.writeByte(failure.ordinal());
    }

    /**
     * 读失败枚举。
     *
     * @param buf 缓冲
     * @return 失败原因
     */
    public static WandFailure readFailure(RegistryFriendlyByteBuf buf) {
        return readOrdinal(buf, WandFailure.values(), "失败原因");
    }

    /**
     * 写删除原因枚举。
     *
     * @param buf    缓冲
     * @param reason 删除原因
     */
    public static void writeReason(RegistryFriendlyByteBuf buf, WandDeleteReason reason) {
        buf.writeByte(reason.ordinal());
    }

    /**
     * 读删除原因枚举。
     *
     * @param buf 缓冲
     * @return 删除原因
     */
    public static WandDeleteReason readReason(RegistryFriendlyByteBuf buf) {
        return readOrdinal(buf, WandDeleteReason.values(), "删除原因");
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

    private static void writeIssue(RegistryFriendlyByteBuf buf, WandIssue issue) {
        buf.writeBoolean(issue.block() != null);
        if (issue.block() != null) {
            writeBlockKey(buf, issue.block());
        }
        writeFailure(buf, issue.failure());
        writeReason(buf, issue.reason());
    }

    private static WandIssue readIssue(RegistryFriendlyByteBuf buf) {
        BlockKey block = buf.readBoolean() ? readBlockKey(buf) : null;
        WandFailure failure = readFailure(buf);
        WandDeleteReason reason = readReason(buf);
        return decode("校验问题", () -> new WandIssue(block, failure, reason));
    }

    private static void writeOutcome(RegistryFriendlyByteBuf buf, WandCommitOutcome outcome) {
        buf.writeBoolean(outcome.ok());
        writeFailure(buf, outcome.failure());
        writeReason(buf, outcome.reason());
        buf.writeBoolean(outcome.nodeId() != null);
        if (outcome.nodeId() != null) {
            buf.writeUUID(outcome.nodeId());
        }
        buf.writeVarInt(outcome.addedMachines());
        buf.writeVarInt(outcome.removedMachines());
        writeBlockKeys(buf, outcome.rejected(), WandLimits.MAX_ISSUES_WIRE);
        buf.writeVarInt(outcome.rejectedCount());
    }

    private static WandCommitOutcome readOutcome(RegistryFriendlyByteBuf buf) {
        boolean ok = buf.readBoolean();
        WandFailure failure = readFailure(buf);
        WandDeleteReason reason = readReason(buf);
        UUID nodeId = buf.readBoolean() ? buf.readUUID() : null;
        int added = buf.readVarInt();
        int removed = buf.readVarInt();
        List<BlockKey> rejected = readBlockKeys(buf, WandLimits.MAX_ISSUES_WIRE);
        int rejectedCount = buf.readVarInt();
        return decode("回执", () -> new WandCommitOutcome(ok, failure, reason, nodeId, added,
                removed, rejected, rejectedCount));
    }

    private static int readCount(RegistryFriendlyByteBuf buf, int limit, String what) {
        int count = buf.readVarInt();
        if (count < 0 || count > limit) {
            throw new DecoderException(what + "数量越界：" + count);
        }
        return count;
    }

    private static <E extends Enum<E>> E readOrdinal(RegistryFriendlyByteBuf buf, E[] values,
            String what) {
        int ordinal = buf.readUnsignedByte();
        if (ordinal >= values.length) {
            throw new DecoderException(what + "序号越界：" + ordinal);
        }
        return values[ordinal];
    }

    private static <T> T decode(String what, Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (IllegalArgumentException e) {
            throw new DecoderException(what + "不合法：" + e.getMessage(), e);
        }
    }
}
