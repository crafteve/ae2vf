package io.github.crafteve.idlefactory.factory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.key.BlockKey;
import io.github.crafteve.idlefactory.node.Node;
import io.github.crafteve.idlefactory.node.NodeCodec;

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
 * <p>只负责 NBT 读写与两条落盘路径：配置行 {@link #put} 与运行时 {@link #flushRuntime}。
 * 两条路径都从当时活的工厂整体重建条目；{@link #put} 会把工厂记进「本 tick 已写」，
 * 同一工厂同一 tick 最多写一次。停服走 {@link #flushAll}。</p>
 *
 * <p>工厂 id 由拥有者派生（{@link #idOf}），不写进存档；存档只记 Owner、锚点与节点。</p>
 */
public final class FactoryStore extends SavedData {
    /** 存档文件名（{@code data/idlefactory_factories.dat}）。 */
    private static final String DATA_NAME = "idlefactory_factories";
    private static final String TAG_FACTORIES = "Factories";
    private static final String TAG_OWNER = "Owner";
    /** 仓库锚点维度；缺这条表示未绑定。 */
    private static final String TAG_ANCHOR_DIM = "AnchorDim";
    /** 仓库锚点坐标（{@link BlockPos#asLong()}）。 */
    private static final String TAG_ANCHOR_POS = "AnchorPos";
    private static final String TAG_NODES = "Nodes";

    private static final SavedData.Factory<FactoryStore> FACTORY =
            new SavedData.Factory<>(FactoryStore::new, FactoryStore::load);

    /** 工厂 id → 存档条目。 */
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    /** 本 tick 已经由 {@link #put} 写过的工厂；{@link #flushRuntime} 跳过它们。 */
    private final Set<UUID> writtenThisTick = new LinkedHashSet<>();
    /** 等下一轮 flush 的工厂。 */
    private final Set<UUID> runtimeDirty = new LinkedHashSet<>();

    private FactoryStore() {
        // SavedData 由工厂方法创建
    }

    /**
     * 一个工厂的存档条目：拥有者、锚点与节点表。
     *
     * @param owner  拥有者玩家 uuid
     * @param anchor 仓库锚点方块；未绑定为 {@code null}
     * @param nodes  节点表
     */
    public record Entry(UUID owner, @Nullable BlockKey anchor, List<Node> nodes) {
        public Entry {
            nodes = List.copyOf(nodes);
        }
    }

    /**
     * 工厂 id 的派生规则：拥有者唯一确定 id。
     *
     * @param owner 拥有者玩家 uuid
     * @return 工厂 id
     */
    public static UUID idOf(UUID owner) {
        return UUID.nameUUIDFromBytes(("idlefactory:" + owner).getBytes(StandardCharsets.UTF_8));
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

    /** 存档里的全部条目（只读）。 */
    public List<Entry> entries() {
        return List.copyOf(this.entries.values());
    }

    /**
     * 配置行落盘：整条重建并记进本 tick 已写。
     *
     * @param factory 发生配置变化的工厂
     */
    public void put(io.github.crafteve.idlefactory.factory.Factory factory) {
        this.entries.put(factory.id(), toEntry(factory));
        this.writtenThisTick.add(factory.id());
        setDirty();
    }

    /**
     * 标一个工厂的运行时数据待落盘。
     *
     * @param factoryId 工厂 id
     */
    public void markRuntimeDirty(UUID factoryId) {
        this.runtimeDirty.add(factoryId);
    }

    /**
     * 每 tick 落盘运行时数据：最多处理 {@code maxPerTick} 个，跳过本 tick 已由 {@link #put} 写过的。
     *
     * @param manager    工厂档案柜
     * @param maxPerTick 本 tick 最多写几个
     */
    public void flushRuntime(FactoryLibrary manager, int maxPerTick) {
        int budget = Math.max(1, maxPerTick);
        int written = 0;
        var iterator = this.runtimeDirty.iterator();
        while (iterator.hasNext() && written < budget) {
            UUID factoryId = iterator.next();
            iterator.remove();
            if (this.writtenThisTick.contains(factoryId)) {
                continue;
            }
            manager.byId(factoryId).ifPresent(factory -> this.entries.put(factory.id(), toEntry(factory)));
            written++;
        }
        if (written > 0) {
            setDirty();
        }
    }

    /**
     * 停服落盘：从当前活的工厂整份重建，顺带清掉已删除工厂的条目。
     *
     * @param manager 工厂档案柜
     */
    public void flushAll(FactoryLibrary manager) {
        this.entries.clear();
        for (io.github.crafteve.idlefactory.factory.Factory factory : manager.all()) {
            this.entries.put(factory.id(), toEntry(factory));
        }
        this.runtimeDirty.clear();
        setDirty();
    }

    /** 新一 tick：清掉「本 tick 已写」。 */
    public void beginTick() {
        this.writtenThisTick.clear();
    }

    private static Entry toEntry(io.github.crafteve.idlefactory.factory.Factory factory) {
        return new Entry(factory.config().owner(), factory.config().storageAnchor(),
                factory.config().nodes().all());
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Entry entry : this.entries.values()) {
            CompoundTag row = new CompoundTag();
            row.putUUID(TAG_OWNER, entry.owner());
            BlockKey anchor = entry.anchor();
            if (anchor != null) {
                row.putString(TAG_ANCHOR_DIM, anchor.dimension().location().toString());
                row.putLong(TAG_ANCHOR_POS, anchor.pos().asLong());
            }
            if (!entry.nodes().isEmpty()) {
                ListTag nodes = new ListTag();
                for (Node node : entry.nodes()) {
                    nodes.add(NodeCodec.save(node, registries));
                }
                row.put(TAG_NODES, nodes);
            }
            list.add(row);
        }
        tag.put(TAG_FACTORIES, list);
        return tag;
    }

    private static FactoryStore load(CompoundTag tag, HolderLookup.Provider registries) {
        FactoryStore store = new FactoryStore();
        ListTag list = tag.getList(TAG_FACTORIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag row = list.getCompound(i);
            if (!row.hasUUID(TAG_OWNER)) {
                continue;
            }
            UUID owner = row.getUUID(TAG_OWNER);
            BlockKey anchor = readAnchor(row);
            List<Node> nodes = new ArrayList<>();
            ListTag nodeList = row.getList(TAG_NODES, Tag.TAG_COMPOUND);
            for (int j = 0; j < nodeList.size(); j++) {
                Node node = NodeCodec.load(nodeList.getCompound(j), registries);
                if (node != null) {
                    nodes.add(node);
                }
            }
            store.entries.put(idOf(owner), new Entry(owner, anchor, nodes));
        }
        return store;
    }

    /**
     * 读仓库锚点。
     *
     * @param row 工厂条目
     * @return 锚点；未绑定或字段非法时为 {@code null}
     */
    @Nullable
    private static BlockKey readAnchor(CompoundTag row) {
        if (!row.contains(TAG_ANCHOR_DIM) || !row.contains(TAG_ANCHOR_POS)) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(row.getString(TAG_ANCHOR_DIM));
        if (id == null) {
            return null;
        }
        return BlockKey.of(ResourceKey.create(Registries.DIMENSION, id),
                BlockPos.of(row.getLong(TAG_ANCHOR_POS)));
    }
}
