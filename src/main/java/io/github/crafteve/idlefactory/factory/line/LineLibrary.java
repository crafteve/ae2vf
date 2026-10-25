package io.github.crafteve.idlefactory.factory.line;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 工厂的被动产线库。
 *
 * <p>按 id 去重；短 id 用于指令，名字用于人读。名字不强制唯一，但创建时会检查重名。</p>
 */
public final class LineLibrary {
    private final Map<UUID, PassiveLine> byId = new LinkedHashMap<>();

    /**
     * 加一条产线。
     *
     * @param line 产线
     * @return 是否真的加入；id 已存在时返回 {@code false}
     */
    public boolean add(PassiveLine line) {
        if (this.byId.containsKey(line.id())) {
            return false;
        }
        this.byId.put(line.id(), line);
        return true;
    }

    /**
     * 批量加，忽略重复。
     *
     * @param lines 产线集合
     */
    public void addAll(Iterable<PassiveLine> lines) {
        for (PassiveLine line : lines) {
            add(line);
        }
    }

    /**
     * 按 id 移除。
     *
     * @param id 产线 id
     * @return 是否真的移除了
     */
    public boolean remove(UUID id) {
        return this.byId.remove(id) != null;
    }

    /** 按 id 查。 */
    public Optional<PassiveLine> byId(UUID id) {
        return Optional.ofNullable(this.byId.get(id));
    }

    /**
     * 按短 id 查（前 8 位）。
     *
     * @param shortId 短 id
     * @return 产线；找不到或前缀有歧义时返回空
     */
    public Optional<PassiveLine> byShortId(String shortId) {
        PassiveLine found = null;
        for (PassiveLine line : this.byId.values()) {
            if (line.shortId().equalsIgnoreCase(shortId)) {
                if (found != null) {
                    return Optional.empty();
                }
                found = line;
            }
        }
        return Optional.ofNullable(found);
    }

    /**
     * 按名字查（精确匹配）。
     *
     * @param name 产线名
     * @return 产线；找不到时返回空
     */
    public Optional<PassiveLine> byName(String name) {
        for (PassiveLine line : this.byId.values()) {
            if (line.name().equals(name)) {
                return Optional.of(line);
            }
        }
        return Optional.empty();
    }

    /** 所有产线（创建顺序）。 */
    public List<PassiveLine> all() {
        return List.copyOf(this.byId.values());
    }

    /** 产线数量。 */
    public int size() {
        return this.byId.size();
    }

    /** 是否为空。 */
    public boolean isEmpty() {
        return this.byId.isEmpty();
    }
}
