package io.github.crafteve.idlefactory.factory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.line.PassiveLine;
import io.github.crafteve.idlefactory.factory.storage.ContainerAnchor;
import io.github.crafteve.idlefactory.factory.storage.StorageAnchor;
import io.github.crafteve.idlefactory.factory.storage.StorageMode;
import io.github.crafteve.idlefactory.factory.machine.MachineRef;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 工厂档案的存档容器。
 *
 * <p>它只负责 NBT 读写，不做业务判断。真正的索引在 {@link FactoryManager}，业务逻辑在
 * {@link FactoryService} / {@link FactoryRuntime}。存档挂在主世界的 {@code DataStorage} 上，
 * 每个存档一份。</p>
 *
 * <p>第一版只存工厂的身份和仓库地址；产线图、IO 配置等结构稳定后再往里加字段。</p>
 */
public final class FactoryStore extends SavedData {
    /** 存档文件名（{@code data/idlefactory_factories.dat}）。 */
    private static final String DATA_NAME = "idlefactory_factories";
    private static final String TAG_FACTORIES = "Factories";
    private static final String TAG_ID = "Id";
    private static final String TAG_OWNER = "Owner";
    private static final String TAG_ANCHOR = "Anchor";
    private static final String TAG_STORAGE_MODE = "StorageMode";
    private static final String TAG_TARGETS = "Targets";
    private static final String TAG_LINES = "Lines";
    private static final String TAG_DETACHED = "Detached";
    private static final String TAG_NEXT_SEQ = "NextSeq";

    private static final SavedData.Factory<FactoryStore> FACTORY =
            new SavedData.Factory<>(FactoryStore::new, FactoryStore::load);

    /** 存档里的工厂记录。 */
    private List<FactoryRecord> records = new ArrayList<>();
    /** 已分配到的工厂序号；用于生成确定性 id。 */
    private long nextSeq;

    private FactoryStore() {
        // SavedData 由工厂方法创建
    }

    /**
     * 取当前存档的工厂档案。
     *
     * @param level 任意服务端维度
     * @return 存档唯一实例
     */
    public static FactoryStore get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /** 当前记录（只读）。 */
    public List<FactoryRecord> records() {
        return List.copyOf(this.records);
    }

    /**
     * 整份替换记录并标脏。
     *
     * @param newRecords 新记录
     */
    public void setRecords(List<FactoryRecord> newRecords) {
        this.records = new ArrayList<>(newRecords);
        setDirty();
    }

    /**
     * 取下一个工厂序号并自增。
     *
     * @return 严格递增的序号
     */
    public long takeNextSeq() {
        setDirty();
        return ++this.nextSeq;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (FactoryRecord record : this.records) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(TAG_ID, record.id());
            entry.putUUID(TAG_OWNER, record.owner());
            if (record.anchor() != null) {
                entry.put(TAG_ANCHOR, record.anchor().save());
            }
            entry.putString(TAG_STORAGE_MODE, record.storageMode().name());
            if (!record.targets().isEmpty()) {
                ListTag targets = new ListTag();
                for (MachineRef target : record.targets()) {
                    targets.add(target.save());
                }
                entry.put(TAG_TARGETS, targets);
            }
            if (!record.lines().isEmpty()) {
                ListTag lines = new ListTag();
                for (PassiveLine line : record.lines()) {
                    lines.add(line.save(registries));
                }
                entry.put(TAG_LINES, lines);
            }
            if (!record.detached().isEmpty()) {
                // 只在非空时写，保持文件干净；旧存档没有这个字段 → 全部活跃
                entry.putIntArray(TAG_DETACHED, encodeUuids(record.detached()));
            }
            list.add(entry);
        }
        tag.put(TAG_FACTORIES, list);
        tag.putLong(TAG_NEXT_SEQ, this.nextSeq);
        return tag;
    }

    private static FactoryStore load(CompoundTag tag, HolderLookup.Provider registries) {
        FactoryStore store = new FactoryStore();
        ListTag list = tag.getList(TAG_FACTORIES, Tag.TAG_COMPOUND);
        List<FactoryRecord> loaded = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.hasUUID(TAG_ID) || !entry.hasUUID(TAG_OWNER)) {
                continue;
            }
            StorageAnchor anchor = entry.contains(TAG_ANCHOR, Tag.TAG_COMPOUND)
                    ? StorageAnchor.load(entry.getCompound(TAG_ANCHOR))
                    : null;
            StorageMode storageMode = readMode(entry, anchor);
            List<MachineRef> targets = new ArrayList<>();
            ListTag targetList = entry.getList(TAG_TARGETS, Tag.TAG_COMPOUND);
            for (int j = 0; j < targetList.size(); j++) {
                MachineRef target = MachineRef.load(targetList.getCompound(j));
                if (target != null) {
                    targets.add(target);
                }
            }
            List<PassiveLine> lines = new ArrayList<>();
            ListTag lineList = entry.getList(TAG_LINES, Tag.TAG_COMPOUND);
            for (int j = 0; j < lineList.size(); j++) {
                PassiveLine line = PassiveLine.load(lineList.getCompound(j), registries);
                if (line != null) {
                    lines.add(line);
                }
            }
            loaded.add(new FactoryRecord(entry.getUUID(TAG_ID), entry.getUUID(TAG_OWNER), anchor,
                    targets, lines, decodeUuids(entry.getIntArray(TAG_DETACHED)), storageMode));
        }
        store.records = loaded;
        store.nextSeq = tag.getLong(TAG_NEXT_SEQ);
        return store;
    }

    /**
     * 读仓库模式；旧档没有字段时按地址类型推断。
     *
     * @param entry  工厂记录
     * @param anchor 已读出的地址，可为空
     * @return 仓库模式
     */
    private static StorageMode readMode(CompoundTag entry, @Nullable StorageAnchor anchor) {
        String raw = entry.getString(TAG_STORAGE_MODE);
        if (!raw.isEmpty()) {
            try {
                return StorageMode.valueOf(raw);
            } catch (IllegalArgumentException ignored) {
                // 未知值按地址类型推断
            }
        }
        return anchor instanceof ContainerAnchor ? StorageMode.CONTAINER : StorageMode.ME;
    }

    /**
     * 一条存档记录：工厂的身份、仓库地址、机器库、被动产线库与回收站。
     *
     * @param id       工厂 id
     * @param owner    拥有者玩家 uuid
     * @param anchor   仓库地址；未绑定为 {@code null}
     * @param targets  机器库里的目标
     * @param lines    被动产线
     * @param detached 玩家已断开、仍留在库里的机器 id（意图，必须写档）
     * @param storageMode 仓库接入模式（ME / 容器）
     */
    public record FactoryRecord(UUID id, UUID owner, @Nullable StorageAnchor anchor,
            List<MachineRef> targets, List<PassiveLine> lines, Set<UUID> detached,
            StorageMode storageMode) {
    }

    /**
     * 把一组 uuid 编成 int 数组（每个 uuid 占 4 个 int）。
     *
     * @param ids uuid 集合
     * @return 扁平数组
     */
    private static int[] encodeUuids(Set<UUID> ids) {
        List<UUID> sorted = new ArrayList<>(ids);
        sorted.sort(java.util.Comparator.comparing(UUID::toString));
        int[] raw = new int[sorted.size() * 4];
        int index = 0;
        for (UUID id : sorted) {
            long most = id.getMostSignificantBits();
            long least = id.getLeastSignificantBits();
            raw[index++] = (int) (most >> 32);
            raw[index++] = (int) most;
            raw[index++] = (int) (least >> 32);
            raw[index++] = (int) least;
        }
        return raw;
    }

    /**
     * 解回 uuid 集合。长度不合法时返回空集（宁可当没有，也不要崩存档）。
     *
     * @param raw 扁平数组
     * @return uuid 集合
     */
    private static Set<UUID> decodeUuids(int[] raw) {
        if (raw == null || raw.length == 0 || raw.length % 4 != 0) {
            return Set.of();
        }
        Set<UUID> ids = new HashSet<>(raw.length / 4);
        for (int i = 0; i < raw.length; i += 4) {
            long most = ((long) raw[i] << 32) | (raw[i + 1] & 0xFFFFFFFFL);
            long least = ((long) raw[i + 2] << 32) | (raw[i + 3] & 0xFFFFFFFFL);
            ids.add(new UUID(most, least));
        }
        return ids;
    }
}
