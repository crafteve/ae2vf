package io.github.crafteve.idlefactory.factory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.line.PassiveLine;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.storage.ContainerAnchor;
import io.github.crafteve.idlefactory.factory.storage.StorageAnchor;
import io.github.crafteve.idlefactory.factory.storage.StorageMode;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 工厂档案的存档容器。
 *
 * <p>它只负责 NBT 读写，不做业务判断。存档只保存配置：工厂身份、仓库地址、机器（含 IO 规则）
 * 和产线（只含机器引用）。运行时数据（仓库在线、诊断、机器失联）一律不写。</p>
 *
 * <p>存档挂在主世界的 {@code DataStorage} 上，每个存档一份。</p>
 */
public final class FactoryStore extends SavedData {
    /** 存档文件名（{@code data/idlefactory_factories.dat}）。 */
    private static final String DATA_NAME = "idlefactory_factories";
    private static final String TAG_FACTORIES = "Factories";
    private static final String TAG_ID = "Id";
    private static final String TAG_OWNER = "Owner";
    private static final String TAG_ANCHOR = "Anchor";
    private static final String TAG_STORAGE_MODE = "StorageMode";
    private static final String TAG_MACHINES = "Machines";
    private static final String TAG_LINES = "Lines";
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
            if (!record.machines().isEmpty()) {
                ListTag machines = new ListTag();
                for (Machine machine : record.machines()) {
                    machines.add(machine.save(registries));
                }
                entry.put(TAG_MACHINES, machines);
            }
            if (!record.lines().isEmpty()) {
                ListTag lines = new ListTag();
                for (PassiveLine line : record.lines()) {
                    lines.add(line.save(registries));
                }
                entry.put(TAG_LINES, lines);
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
            List<Machine> machines = new ArrayList<>();
            ListTag machineList = entry.getList(TAG_MACHINES, Tag.TAG_COMPOUND);
            for (int j = 0; j < machineList.size(); j++) {
                Machine machine = Machine.load(machineList.getCompound(j), registries);
                if (machine != null) {
                    machines.add(machine);
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
                    storageMode, machines, lines));
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
     * 一条存档记录：工厂身份、仓库地址、机器表与产线表。
     *
     * <p>机器自己带 IO 规则；产线只存机器 id 引用。</p>
     *
     * @param id          工厂 id
     * @param owner       拥有者玩家 uuid
     * @param anchor      仓库地址；未绑定为 {@code null}
     * @param storageMode 仓库接入模式（ME / 容器）
     * @param machines    机器表（含 IO 规则）
     * @param lines       产线表（只含机器引用）
     */
    public record FactoryRecord(UUID id, UUID owner, @Nullable StorageAnchor anchor,
            StorageMode storageMode, List<Machine> machines, List<PassiveLine> lines) {
    }
}
