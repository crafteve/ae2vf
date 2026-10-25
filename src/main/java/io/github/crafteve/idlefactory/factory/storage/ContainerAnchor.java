package io.github.crafteve.idlefactory.factory.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * 由若干方块容器组成的仓库地址。
 *
 * <p>不可变：增删改都返回新实例，工厂替换地址时整体换掉，不会出现改到一半的状态。</p>
 *
 * <p>容器上限 {@link #MAX_CONTAINERS} 是性能护栏：IO 每 5 tick 一轮，每轮会遍历所有容器的槽。</p>
 *
 * @param containers 容器列表
 */
public record ContainerAnchor(List<ContainerRef> containers) implements StorageAnchor {
    /** 一个仓库最多挂多少个容器。 */
    public static final int MAX_CONTAINERS = 16;

    private static final String TAG_TYPE = "Type";
    private static final String TAG_CONTAINERS = "Containers";

    public ContainerAnchor {
        containers = List.copyOf(containers);
    }

    /** 是否没有容器。 */
    public boolean isEmpty() {
        return this.containers.isEmpty();
    }

    /**
     * 是否已经包含这个容器。
     *
     * @param id 容器 id
     * @return 已包含时为 {@code true}
     */
    public boolean contains(UUID id) {
        for (ContainerRef ref : this.containers) {
            if (ref.id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 加入一个容器；已存在则替换，满了则原样返回。
     *
     * @param ref 容器
     * @return 新地址
     */
    public ContainerAnchor with(ContainerRef ref) {
        List<ContainerRef> next = new ArrayList<>(this.containers);
        for (int i = 0; i < next.size(); i++) {
            if (next.get(i).id().equals(ref.id())) {
                next.set(i, ref);
                return new ContainerAnchor(next);
            }
        }
        next.add(ref);
        return new ContainerAnchor(next);
    }

    /**
     * 移除一个容器。
     *
     * @param id 容器 id
     * @return 新地址
     */
    public ContainerAnchor without(UUID id) {
        List<ContainerRef> next = new ArrayList<>(this.containers);
        next.removeIf(ref -> ref.id().equals(id));
        return new ContainerAnchor(next);
    }

    /**
     * 改一个容器的优先级。
     *
     * @param id       容器 id
     * @param priority 新优先级
     * @return 新地址
     */
    public ContainerAnchor withPriority(UUID id, int priority) {
        List<ContainerRef> next = new ArrayList<>(this.containers.size());
        for (ContainerRef ref : this.containers) {
            next.add(ref.id().equals(id) ? ref.withPriority(priority) : ref);
        }
        return new ContainerAnchor(next);
    }

    /**
     * 按短 id 查容器。
     *
     * @param shortId 短 id
     * @return 容器；找不到或前缀有歧义时为空
     */
    public Optional<ContainerRef> byShortId(String shortId) {
        ContainerRef found = null;
        for (ContainerRef ref : this.containers) {
            if (ref.shortId().equalsIgnoreCase(shortId)) {
                if (found != null) {
                    return Optional.empty();
                }
                found = ref;
            }
        }
        return Optional.ofNullable(found);
    }

    @Override
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG_TYPE, StorageAnchor.TYPE_CONTAINER);
        ListTag list = new ListTag();
        for (ContainerRef ref : this.containers) {
            list.add(ref.save());
        }
        tag.put(TAG_CONTAINERS, list);
        return tag;
    }

    @Override
    public String describe() {
        return "容器 ×" + this.containers.size();
    }

    /**
     * 从 NBT 读回来。
     *
     * @param tag 存档标签
     * @return 地址；没有有效容器时返回 {@code null}
     */
    @Nullable
    public static ContainerAnchor load(CompoundTag tag) {
        ListTag list = tag.getList(TAG_CONTAINERS, Tag.TAG_COMPOUND);
        List<ContainerRef> refs = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            ContainerRef ref = ContainerRef.load(list.getCompound(i));
            if (ref != null) {
                refs.add(ref);
            }
        }
        return refs.isEmpty() ? null : new ContainerAnchor(refs);
    }
}
