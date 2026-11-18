package io.github.crafteve.idlefactory.factory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.location.BlockKey;
import io.github.crafteve.idlefactory.node.Node;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 工厂档案的存档容器。
 *
 * <p>它只负责 NBT 读写，不做业务判断。存档只保存配置：工厂身份、仓库锚点与节点（含机器、
 * IO 规则与 provider）。运行时数据（仓库在线、诊断、健康）一律不写。</p>
 *
 * <p>存档挂在主世界的 {@code DataStorage} 上，每个存档一份。</p>
 */
public final class FactoryStore extends SavedData {
    /** 存档文件名（{@code data/idlefactory_factories.dat}）。 */
    private static final String DATA_NAME = "idlefactory_factories";
    private static final String TAG_FACTORIES = "Factories";
    private static final String TAG_ID = "Id";
    private static final String TAG_OWNER = "Owner";
    /** 仓库锚点维度；缺这条表示未绑定。 */
    private static final String TAG_ANCHOR_DIM = "AnchorDim";
    /** 仓库锚点坐标（{@link BlockPos#asLong()}）。 */
    private static final String TAG_ANCHOR_POS = "AnchorPos";
    private static final String TAG_NODES = "Nodes";
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
            BlockKey anchor = record.anchor();
            if (anchor != null) {
                entry.putString(TAG_ANCHOR_DIM, anchor.dimension().location().toString());
                entry.putLong(TAG_ANCHOR_POS, anchor.pos().asLong());
            }
            if (!record.nodes().isEmpty()) {
                ListTag nodes = new ListTag();
                for (Node node : record.nodes()) {
                    nodes.add(node.save(registries));
                }
                entry.put(TAG_NODES, nodes);
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
            BlockKey anchor = readAnchor(entry);
            List<Node> nodes = new ArrayList<>();
            ListTag nodeList = entry.getList(TAG_NODES, Tag.TAG_COMPOUND);
            for (int j = 0; j < nodeList.size(); j++) {
                Node node = Node.load(nodeList.getCompound(j), registries);
                if (node != null) {
                    nodes.add(node);
                }
            }
            loaded.add(new FactoryRecord(entry.getUUID(TAG_ID), entry.getUUID(TAG_OWNER), anchor,
                    nodes));
        }
        store.records = loaded;
        store.nextSeq = tag.getLong(TAG_NEXT_SEQ);
        return store;
    }

    /**
     * 读仓库锚点。
     *
     * <p>缺维度键或维度名非法都按「未绑定」处理：锚点是可选配置，读不出来不该拖累整个工厂。</p>
     *
     * @param entry 工厂记录标签
     * @return 锚点；未绑定或字段非法时为 {@code null}
     */
    @Nullable
    private static BlockKey readAnchor(CompoundTag entry) {
        if (!entry.contains(TAG_ANCHOR_DIM) || !entry.contains(TAG_ANCHOR_POS)) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(entry.getString(TAG_ANCHOR_DIM));
        if (id == null) {
            return null;
        }
        return BlockKey.of(ResourceKey.create(Registries.DIMENSION, id),
                BlockPos.of(entry.getLong(TAG_ANCHOR_POS)));
    }

    /**
     * 一条存档记录：工厂身份、仓库锚点与节点表。
     *
     * <p>节点自己带机器、IO 规则与 provider；{@code NextSeq} 仍是存档根级字段，
     * 不属于任何工厂记录。</p>
     *
     * @param id     工厂 id
     * @param owner  拥有者玩家 uuid
     * @param anchor 仓库锚点方块；未绑定为 {@code null}
     * @param nodes  节点表（含机器、IO 规则与 provider）
     */
    public record FactoryRecord(UUID id, UUID owner, @Nullable BlockKey anchor, List<Node> nodes) {
    }
}
