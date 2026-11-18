package io.github.crafteve.idlefactory.factory;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.FactoryStore.FactoryRecord;
import io.github.crafteve.idlefactory.node.Node;
import io.github.crafteve.idlefactory.node.NodeLibrary;

/**
 * 工厂档案柜：内存里的索引。
 *
 * <p>两张表：按工厂 id 查、按拥有者 uuid 查。第一版一人一个工厂，所以 {@code byOwner} 是
 * 一对一；以后做队伍版时把它扩成一对多即可，{@code byId} 不用动。</p>
 */
public final class FactoryManager {
    private final Map<UUID, Factory> byId = new LinkedHashMap<>();
    private final Map<UUID, Factory> byOwner = new HashMap<>();

    /**
     * 从存档记录重建工厂。启动时调一次。
     *
     * <p>把记录里的节点逐个灌进节点库即可；节点自带机器与规则。</p>
     *
     * <p>节点库拒绝登记（id / 机器 / 方块冲突或超过硬上限）时保留先登记的那条并打 warn：
     * 静默丢弃会让下一次 {@code markDirty → save} 永久抹掉这些节点，必须留下可排查的痕迹。
     * 工厂记录本身因 id 或 owner 冲突被拒同样只记日志，保持「先登记的优先」。</p>
     *
     * @param records 存档里的工厂记录
     */
    public void loadFrom(List<FactoryRecord> records) {
        this.byId.clear();
        this.byOwner.clear();
        for (FactoryRecord record : records) {
            FactoryConfig config = new FactoryConfig(record.owner(), record.storageMode(),
                    record.anchor());
            Factory factory = new Factory(record.id(), config);
            for (Node node : record.nodes()) {
                NodeLibrary.Result added = factory.restoreNode(node);
                if (added != NodeLibrary.Result.ADDED) {
                    IdleFactory.LOGGER.warn(
                            "Idle Factory: 载入工厂 {} 时节点 {} 未登记（{}），保留先登记的那个",
                            record.id(), shortId(node), addResultText(added));
                }
            }
            if (!add(factory)) {
                IdleFactory.LOGGER.warn(
                        "Idle Factory: 载入工厂 {}（拥有者 {}）失败：id 或拥有者与先登记的记录冲突，本条已忽略",
                        record.id(), record.owner());
            }
        }
    }

    /**
     * 节点未登记原因的中文文案，只用于日志。
     *
     * @param result 节点库的写入结果
     * @return 如「节点库已满」
     */
    private static String addResultText(NodeLibrary.Result result) {
        return switch (result) {
            case ADDED -> "已加入";
            case REPLACED -> "已替换";
            case REMOVED -> "已移除";
            case DUPLICATE -> "id 重复";
            case CONFLICT -> "方块已被占用";
            case FULL -> "节点库已满";
            case NOT_FOUND -> "找不到节点";
        };
    }

    /**
     * 取 id 的前 8 位，只用于日志。
     *
     * @param node 节点
     * @return 短 id
     */
    private static String shortId(Node node) {
        return node.id().toString().substring(0, 8);
    }

    /**
     * 导出成存档记录。停止时调一次。
     *
     * @return 当前所有工厂的记录
     */
    public List<FactoryRecord> toRecords() {
        return this.byId.values().stream()
                .map(factory -> new FactoryRecord(factory.id(), factory.config().owner(),
                        factory.config().storageAnchor(), factory.config().storageMode(),
                        factory.config().nodes().all()))
                .toList();
    }

    /**
     * 加入一个工厂。id 或 owner 已存在时不做任何事。
     *
     * @param factory 工厂
     * @return 是否真的加入了
     */
    public boolean add(Factory factory) {
        if (this.byId.containsKey(factory.id())
                || this.byOwner.containsKey(factory.config().owner())) {
            return false;
        }
        this.byId.put(factory.id(), factory);
        this.byOwner.put(factory.config().owner(), factory);
        return true;
    }

    /**
     * 移除一个工厂。
     *
     * @param id 工厂 id
     * @return 是否存在并移除
     */
    public boolean remove(UUID id) {
        Factory removed = this.byId.remove(id);
        if (removed == null) {
            return false;
        }
        this.byOwner.remove(removed.config().owner());
        return true;
    }

    /** 按工厂 id 查。 */
    public Optional<Factory> byId(UUID id) {
        return Optional.ofNullable(this.byId.get(id));
    }

    /** 按拥有者查；第一版一人一个。 */
    public Optional<Factory> byOwner(UUID ownerId) {
        return Optional.ofNullable(this.byOwner.get(ownerId));
    }

    /** 所有工厂（创建顺序）。 */
    public Collection<Factory> all() {
        return Collections.unmodifiableCollection(this.byId.values());
    }

    /** 当前是否有工厂。 */
    public boolean isEmpty() {
        return this.byId.isEmpty();
    }
}
