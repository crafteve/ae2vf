package io.github.crafteve.idlefactory.node;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.location.BlockKey;
import io.github.crafteve.idlefactory.machine.Machine;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/**
 * 一个节点：随机 id + 名字 + 类型方块 id + 节点级 IO / provider + 成员机器。
 *
 * <p>节点是<b>浅不可变聚合</b>：字段全部 final，结构变更返回新实例；{@link NodeIo} 与
 * {@link NodeCraft} 是可变的，由新旧实例共享，所以加一台机器不会复制规则和样板。</p>
 *
 * <p>结构变更（加 / 删 / 替换机器、换类型方块）会把 {@link #topologyRevision()} 加一，
 * 供运行时判断是否需要重建 AE2 节点；改名、改规则、改阻挡不是结构变更，不加版本。</p>
 *
 * <p>节点不存维度：维度在每个 {@link BlockKey} 上，节点内机器可以跨维度。</p>
 */
public final class Node {
    /** 节点名字长度硬上限；配置只能调低。 */
    public static final int HARD_MAX_NAME_LENGTH = 32767;

    private static final String TAG_ID = "Id";
    private static final String TAG_NAME = "Name";
    private static final String TAG_TYPE_BLOCK_ID = "TypeBlockId";
    private static final String TAG_IO = "Io";
    /** 样板 / 队列聚合；旧档写的是 {@code Provider}，读侧兼容。 */
    private static final String TAG_CRAFT = "Craft";
    private static final String TAG_MACHINES = "Machines";

    private final UUID id;
    @Nullable
    private final String name;
    private final ResourceLocation typeBlockId;
    private final NodeIo io;
    private final NodeCraft provider;
    private final List<Machine> machines;
    private final long topologyRevision;

    /**
     * @param id               节点 id
     * @param name             玩家起的名字；{@code null} 表示用方块名
     * @param typeBlockId      类型方块 id，必须非空
     * @param io               节点 IO 规则
     * @param provider         节点样板供应器数据
     * @param machines         成员机器，至少一台，顺序即加入顺序
     * @param topologyRevision 结构版本；新建节点从 0 开始
     */
    public Node(UUID id, @Nullable String name, ResourceLocation typeBlockId, NodeIo io,
            NodeCraft provider, List<Machine> machines, long topologyRevision) {
        if (typeBlockId == null) {
            throw new IllegalArgumentException("节点必须有类型方块 id");
        }
        if (machines.isEmpty()) {
            throw new IllegalArgumentException("节点至少要有一台机器");
        }
        this.id = id;
        this.name = name == null || name.isEmpty() ? null : name;
        this.typeBlockId = typeBlockId;
        this.io = io;
        this.provider = provider;
        this.machines = List.copyOf(machines);
        this.topologyRevision = topologyRevision;
    }

    /**
     * 新建一个只有一个方块的节点：节点 id 与机器 id 都随机生成，1:1 时也不再相等。
     *
     * @param name   名字；{@code null} 表示用方块名
     * @param typeId 类型方块 id
     * @param block  唯一方块
     * @return 新节点
     */
    public static Node of(@Nullable String name, ResourceLocation typeId, BlockKey block) {
        return new Node(UUID.randomUUID(), name, typeId, new NodeIo(),
                new NodeCraft(), List.of(new Machine(UUID.randomUUID(), block)), 0L);
    }

    /** 节点 id。 */
    public UUID id() {
        return this.id;
    }

    /** 玩家起的名字；{@code null} 表示用方块名。 */
    @Nullable
    public String name() {
        return this.name;
    }

    /** 类型方块 id：节点内所有机器的方块 id 必须等于它。 */
    public ResourceLocation typeBlockId() {
        return this.typeBlockId;
    }

    /** 节点 IO 规则；结构变更时新旧节点共享同一个对象。 */
    public NodeIo io() {
        return this.io;
    }

    /** 节点样板供应器数据；结构变更时新旧节点共享同一个对象。 */
    public NodeCraft provider() {
        return this.provider;
    }

    /** 成员机器（加入顺序）。 */
    public List<Machine> machines() {
        return this.machines;
    }

    /** 结构版本；机器 / 方块 / 类型变化时加一。 */
    public long topologyRevision() {
        return this.topologyRevision;
    }

    /** 机器台数。 */
    public int machineCount() {
        return this.machines.size();
    }

    /** 输入 + 输出规则总数。 */
    public int ruleCount() {
        return this.io.ruleCount();
    }

    /** 已放样板数。 */
    public int patternCount() {
        return this.provider.patternCount();
    }

    /**
     * 按机器 id 找成员。
     *
     * @param machineId 机器 id
     * @return 成员；不属于本节点时为空
     */
    public Optional<Machine> machineOf(UUID machineId) {
        for (Machine machine : this.machines) {
            if (machine.id().equals(machineId)) {
                return Optional.of(machine);
            }
        }
        return Optional.empty();
    }

    /**
     * 按方块位置找所属机器。
     *
     * @param key 方块位置
     * @return 成员；不属于本节点时为空
     */
    public Optional<Machine> machineAt(BlockKey key) {
        for (Machine machine : this.machines) {
            if (machine.block().equals(key)) {
                return Optional.of(machine);
            }
        }
        return Optional.empty();
    }

    /**
     * 改名；不改变结构版本。
     *
     * @param next 新名字；{@code null} 或空串表示清除
     * @return 新节点
     */
    public Node withName(@Nullable String next) {
        return new Node(this.id, next, this.typeBlockId, this.io, this.provider, this.machines,
                this.topologyRevision);
    }

    /**
     * 换类型方块；属于结构变更，结构版本加一。
     *
     * @param next 新类型方块 id
     * @return 新节点
     */
    public Node withTypeBlockId(ResourceLocation next) {
        return new Node(this.id, this.name, next, this.io, this.provider, this.machines,
                this.topologyRevision + 1L);
    }

    /**
     * 加一台机器；属于结构变更，结构版本加一。
     *
     * <p>同一个机器 id 已在节点里时替换它。</p>
     *
     * @param machine 新机器
     * @return 新节点
     */
    public Node withAddedMachine(Machine machine) {
        List<Machine> next = new ArrayList<>(this.machines);
        for (int i = 0; i < next.size(); i++) {
            if (next.get(i).id().equals(machine.id())) {
                next.set(i, machine);
                return new Node(this.id, this.name, this.typeBlockId, this.io, this.provider, next,
                        this.topologyRevision + 1L);
            }
        }
        next.add(machine);
        return new Node(this.id, this.name, this.typeBlockId, this.io, this.provider, next,
                this.topologyRevision + 1L);
    }

    /**
     * 删一台机器；属于结构变更，结构版本加一。
     *
     * <p>删掉最后一台机器时返回自身：节点至少一台机器，调用方应先确认再删节点。</p>
     *
     * @param machineId 机器 id
     * @return 新节点
     */
    public Node withRemovedMachine(UUID machineId) {
        if (this.machines.size() <= 1) {
            return this;
        }
        List<Machine> next = new ArrayList<>(this.machines.size());
        boolean removed = false;
        for (Machine machine : this.machines) {
            if (machine.id().equals(machineId)) {
                removed = true;
            } else {
                next.add(machine);
            }
        }
        if (!removed) {
            return this;
        }
        return new Node(this.id, this.name, this.typeBlockId, this.io, this.provider, next,
                this.topologyRevision + 1L);
    }

    /**
     * 序列化成 NBT：身份 + 类型 + IO + provider + 机器表。
     *
     * @param registries 注册表访问，物品组件编码需要
     * @return 新标签
     */
    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(TAG_ID, this.id);
        if (this.name != null) {
            tag.putString(TAG_NAME, this.name);
        }
        tag.putString(TAG_TYPE_BLOCK_ID, this.typeBlockId.toString());
        tag.put(TAG_IO, this.io.save(registries));
        tag.put(TAG_CRAFT, this.provider.save(registries));
        ListTag list = new ListTag();
        for (Machine machine : this.machines) {
            list.add(machine.save());
        }
        tag.put(TAG_MACHINES, list);
        return tag;
    }

    /**
     * 从 NBT 读回来；缺 id / 类型方块 / 机器时丢弃。
     *
     * <p>坏机器逐个丢弃，读完一台不剩才丢弃整份节点。</p>
     *
     * @param tag        存档标签
     * @param registries 注册表访问，物品组件解码需要
     * @return 节点；无法恢复时为 {@code null}
     */
    @Nullable
    public static Node load(CompoundTag tag, HolderLookup.Provider registries) {
        if (!tag.hasUUID(TAG_ID)) {
            return null;
        }
        ResourceLocation typeId = ResourceLocation.tryParse(tag.getString(TAG_TYPE_BLOCK_ID));
        if (typeId == null) {
            return null;
        }
        ListTag machineList = tag.getList(TAG_MACHINES, Tag.TAG_COMPOUND);
        List<Machine> machines = new ArrayList<>(machineList.size());
        for (int i = 0; i < machineList.size(); i++) {
            Machine machine = Machine.load(machineList.getCompound(i));
            if (machine != null) {
                machines.add(machine);
            }
        }
        if (machines.isEmpty()) {
            return null;
        }
        NodeIo io = NodeIo.load(tag.getCompound(TAG_IO), registries, tag.getUUID(TAG_ID));
        // 旧档写的是 Provider；新档写 Craft，读侧两种都认。
        CompoundTag craftTag = tag.contains(TAG_CRAFT) ? tag.getCompound(TAG_CRAFT)
                : tag.getCompound("Provider");
        NodeCraft provider = NodeCraft.load(craftTag, registries);
        String name = tag.contains(TAG_NAME) ? tag.getString(TAG_NAME) : null;
        return new Node(tag.getUUID(TAG_ID), name, typeId, io, provider, machines, 0L);
    }
}
