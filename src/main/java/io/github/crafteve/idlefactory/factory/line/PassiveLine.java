package io.github.crafteve.idlefactory.factory.line;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * 一条被动产线。
 *
 * <p>它管一组 IO 组（每台机器一个），自己只有「运行 / 停止」两态。第一版不执行搬运，
 * {@link #running()} 只是给后面的执行器看的开关。</p>
 */
public final class PassiveLine {
    private static final String TAG_ID = "Id";
    private static final String TAG_NAME = "Name";
    private static final String TAG_RUNNING = "Running";
    private static final String TAG_GROUPS = "Groups";

    private final UUID id;
    private final String name;
    private boolean running;
    private final List<IoGroup> groups = new ArrayList<>();

    /**
     * @param id   产线 id
     * @param name 产线名
     */
    public PassiveLine(UUID id, String name) {
        this.id = id;
        this.name = name;
    }

    /** 产线 id。 */
    public UUID id() {
        return this.id;
    }

    /** 产线名。 */
    public String name() {
        return this.name;
    }

    /** 是否运行中。 */
    public boolean running() {
        return this.running;
    }

    /** 启动 / 停止。第一版只改开关，不触发搬运。 */
    public void setRunning(boolean running) {
        this.running = running;
    }

    /** 所有 IO 组（只读）。 */
    public List<IoGroup> groups() {
        return List.copyOf(this.groups);
    }

    /**
     * 加一个 IO 组。
     *
     * @param group 组
     * @return 是否加入；这台机器已在产线里时返回 {@code false}
     */
    public boolean addGroup(IoGroup group) {
        if (groupByMachine(group.machineId()).isPresent()) {
            return false;
        }
        this.groups.add(group);
        return true;
    }

    /**
     * 按机器 id 移除组。
     *
     * @param machineId 机器库里的机器 id
     * @return 是否真的移除了
     */
    public boolean removeGroupByMachine(UUID machineId) {
        return this.groups.removeIf(group -> group.machineId().equals(machineId));
    }

    /**
     * 批量移除机器（机器库对账时用）。
     *
     * @param machineIds 机器 id 集合
     */
    public void removeGroupsFor(Collection<UUID> machineIds) {
        this.groups.removeIf(group -> machineIds.contains(group.machineId()));
    }

    /** 按机器 id 找组。 */
    public Optional<IoGroup> groupByMachine(UUID machineId) {
        for (IoGroup group : this.groups) {
            if (group.machineId().equals(machineId)) {
                return Optional.of(group);
            }
        }
        return Optional.empty();
    }

    /** 给界面和指令用的短 id（前 8 位）。 */
    public String shortId() {
        return this.id.toString().substring(0, 8);
    }

    /**
     * 序列化成 NBT。
     *
     * @return 新标签
     */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(TAG_ID, this.id);
        tag.putString(TAG_NAME, this.name);
        tag.putBoolean(TAG_RUNNING, this.running);
        ListTag groupList = new ListTag();
        for (IoGroup group : this.groups) {
            groupList.add(group.save());
        }
        tag.put(TAG_GROUPS, groupList);
        return tag;
    }

    /**
     * 从 NBT 读回来。
     *
     * @param tag 存档标签
     * @return 产线；字段缺失时返回 {@code null}
     */
    @Nullable
    public static PassiveLine load(CompoundTag tag) {
        if (!tag.hasUUID(TAG_ID)) {
            return null;
        }
        PassiveLine line = new PassiveLine(tag.getUUID(TAG_ID), tag.getString(TAG_NAME));
        line.running = tag.getBoolean(TAG_RUNNING);
        ListTag groupList = tag.getList(TAG_GROUPS, Tag.TAG_COMPOUND);
        for (int i = 0; i < groupList.size(); i++) {
            IoGroup group = IoGroup.load(groupList.getCompound(i));
            if (group != null) {
                line.groups.add(group);
            }
        }
        return line;
    }
}
