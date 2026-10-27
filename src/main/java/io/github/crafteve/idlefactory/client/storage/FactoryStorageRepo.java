package io.github.crafteve.idlefactory.client.storage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import io.github.crafteve.idlefactory.factory.resource.ResourceKey;
import io.github.crafteve.idlefactory.network.payload.FactoryStorageS2C;

/**
 * 客户端仓库视图。
 *
 * <p>模仿 AE2 Repo 的工作方式：服务端每次给完整快照，这里按 {@link ResourceKey} 维护稳定身份，
 * 只在快照之间做差异，不把点击命中和搜索直接绑在原始列表下标上。这样仓库数量刷新时，选中格、
 * 过滤结果和滚动位置不会因为服务端重排而乱跳。</p>
 *
 * <p>不直接复用 AE2 的 {@code Repo}：它绑死 {@code GridInventoryEntry}、{@code AEKey} 和
 * {@code MEStorageMenu}，而本项目的仓库后端可能是容器组。</p>
 */
public final class FactoryStorageRepo {
    private final Map<ResourceKey, FactoryStorageS2C.Entry> byKey = new LinkedHashMap<>();
    private List<FactoryStorageS2C.Entry> all = List.of();
    private List<FactoryStorageS2C.Entry> view = List.of();
    private Predicate<FactoryStorageS2C.Entry> filter = entry -> true;

    /**
     * 用服务端快照替换当前仓库。
     *
     * <p>已有 key 沿用上一次的显示顺序，新 key 才按服务端顺序追加。仓库数量变化不重排，
     * Shift 连续取物时不会因为服务端按数量降序重排而跳格。</p>
     */
    public void replace(List<FactoryStorageS2C.Entry> entries) {
        Map<ResourceKey, FactoryStorageS2C.Entry> incoming = new LinkedHashMap<>();
        for (FactoryStorageS2C.Entry entry : entries) {
            incoming.put(entry.key(), entry);
        }

        Map<ResourceKey, FactoryStorageS2C.Entry> ordered = new LinkedHashMap<>();
        for (ResourceKey key : this.byKey.keySet()) {
            FactoryStorageS2C.Entry entry = incoming.remove(key);
            if (entry != null) {
                ordered.put(key, entry);
            }
        }
        ordered.putAll(incoming);

        this.byKey.clear();
        this.byKey.putAll(ordered);
        this.all = List.copyOf(this.byKey.values());
        this.rebuildView();
    }

    /** 清空仓库；没有工厂时使用。 */
    public void clear() {
        this.byKey.clear();
        this.all = List.of();
        this.view = List.of();
    }

    /** 设置搜索过滤；传 {@code null} 表示不过滤。 */
    public void setFilter(Predicate<FactoryStorageS2C.Entry> newFilter) {
        this.filter = newFilter == null ? entry -> true : newFilter;
        this.rebuildView();
    }

    /** 当前全部条目，按服务端顺序。 */
    public List<FactoryStorageS2C.Entry> all() {
        return this.all;
    }

    /** 当前过滤后的可见条目。 */
    public List<FactoryStorageS2C.Entry> view() {
        return this.view;
    }

    /** 当前可见条目数。 */
    public int size() {
        return this.view.size();
    }

    /** 当前可见条目；越界返回 {@code null}。 */
    public FactoryStorageS2C.Entry at(int index) {
        return index >= 0 && index < this.view.size() ? this.view.get(index) : null;
    }

    /** 某资源是否还在仓库里。 */
    public boolean contains(ResourceKey key) {
        return this.byKey.containsKey(key);
    }

    /** 某资源在当前可见视图中的下标；不存在返回 -1。 */
    public int indexOf(ResourceKey key) {
        for (int i = 0; i < this.view.size(); i++) {
            if (this.view.get(i).key().equals(key)) {
                return i;
            }
        }
        return -1;
    }

    private void rebuildView() {
        if (this.filter == null) {
            this.view = this.all;
            return;
        }
        this.view = this.all.stream().filter(this.filter).toList();
    }
}
