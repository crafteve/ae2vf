package io.github.crafteve.idlefactory.factory;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.crafteve.idlefactory.factory.FactoryStore.FactoryRecord;

/**
 * 工厂档案柜：内存里的索引。
 *
 * <p>两张表：按工厂 id 查、按拥有者 uuid 查。第一版一人一个工厂，所以 {@code byOwner} 是
 * 一对一；以后做队伍版时把它扩成一对多即可，{@code byId} 不用动。</p>
 */
public final class FactoryManager {
    private final Map<UUID, Factory> byId = new LinkedHashMap<>();
    private final Map<UUID, UUID> byOwner = new HashMap<>();

    /**
     * 从存档记录重建工厂。启动时调一次。
     *
     * @param records 存档里的工厂记录
     */
    public void loadFrom(List<FactoryRecord> records) {
        this.byId.clear();
        this.byOwner.clear();
        for (FactoryRecord record : records) {
            Factory factory = new Factory(record.id(), record.owner());
            factory.bindAnchor(record.anchor());
            factory.machines().addAll(record.targets());
            factory.lines().addAll(record.lines());
            add(factory);
        }
    }

    /**
     * 导出成存档记录。停止时调一次。
     *
     * @return 当前所有工厂的记录
     */
    public List<FactoryRecord> toRecords() {
        return this.byId.values().stream()
                .map(factory -> new FactoryRecord(factory.id(), factory.owner(),
                        factory.storageLink().anchor(), factory.machines().all(),
                        factory.lines().all()))
                .toList();
    }

    /**
     * 加入一个工厂。id 或 owner 已存在时不做任何事。
     *
     * @param factory 工厂
     * @return 是否真的加入了
     */
    public boolean add(Factory factory) {
        if (this.byId.containsKey(factory.id()) || this.byOwner.containsKey(factory.owner())) {
            return false;
        }
        this.byId.put(factory.id(), factory);
        this.byOwner.put(factory.owner(), factory.id());
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
        this.byOwner.remove(removed.owner());
        return true;
    }

    /** 按工厂 id 查。 */
    public Optional<Factory> byId(UUID id) {
        return Optional.ofNullable(this.byId.get(id));
    }

    /** 按拥有者查；第一版一人一个。 */
    public Optional<Factory> byOwner(UUID ownerId) {
        UUID id = this.byOwner.get(ownerId);
        return id == null ? Optional.empty() : byId(id);
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
