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
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.machine.MachineLibrary;

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
     * <p>P1 删除产线后只剩机器：把记录里的机器逐台灌进机器库即可。</p>
     *
     * <p>机器库拒绝登记（同 id 重复或超过 {@link MachineLibrary#MAX_MACHINES}）时保留先登记的
     * 那条并打 warn：静默丢弃会让下一次 {@code markDirty → save} 永久抹掉这些机器，必须留下
     * 可排查的痕迹。工厂记录本身因 id 或 owner 冲突被拒同样只记日志，保持「先登记的优先」。</p>
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
            for (Machine machine : record.machines()) {
                MachineLibrary.AddResult added = factory.restoreMachine(machine);
                if (added != MachineLibrary.AddResult.ADDED) {
                    IdleFactory.LOGGER.warn(
                            "Idle Factory: 载入工厂 {} 时机器 {} 未登记（{}），保留先登记的那台",
                            record.id(), machine.ref().shortId(), addResultText(added));
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
     * 机器未登记原因的中文文案，只用于日志。
     *
     * @param result 机器库的加入结果
     * @return 如「机器库已满」
     */
    private static String addResultText(MachineLibrary.AddResult result) {
        return switch (result) {
            case ADDED -> "已加入";
            case DUPLICATE -> "id 重复";
            case FULL -> "机器库已满";
        };
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
                        factory.config().machines().all()))
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
