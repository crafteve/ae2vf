package io.github.crafteve.idlefactory.factory.line;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * 一条被动产线：一组机器的引用。
 *
 * <p>产线不再持有 IO 规则；机器自己带规则。从产线移除机器只是删引用，机器和规则都留在机器库。</p>
 *
 * <p>第一版一台机器只能属于一条产线；加入时由调用方保证不重复。</p>
 */
public final class PassiveLine {
    private static final String TAG_ID = "Id";
    private static final String TAG_NAME = "Name";
    private static final String TAG_RUNNING = "Running";
    private static final String TAG_MACHINE_IDS = "MachineIds";
    private static final String TAG_MACHINE_ID = "Id";

    private final UUID id;
    private String name;
    private boolean running;
    private final List<UUID> machineIds = new ArrayList<>();

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

    /**
     * 只改产线名，id 不变。
     *
     * @param name 新名字（已由调用方 trim）
     */
    public void rename(String name) {
        this.name = name;
    }

    /** 是否运行中。 */
    public boolean running() {
        return this.running;
    }

    /** 启动 / 停止。 */
    public void setRunning(boolean running) {
        this.running = running;
    }

    /** 产线里的机器 id（只读，保留登记顺序）。 */
    public List<UUID> machineIds() {
        return List.copyOf(this.machineIds);
    }

    /** 机器数。 */
    public int machineCount() {
        return this.machineIds.size();
    }

    /** 是否包含这台机器。 */
    public boolean containsMachine(UUID machineId) {
        return this.machineIds.contains(machineId);
    }

    /**
     * 加一台机器引用。
     *
     * @param machineId 机器 id
     * @return 是否加入；已存在时返回 {@code false}
     */
    public boolean addMachine(UUID machineId) {
        if (this.machineIds.contains(machineId)) {
            return false;
        }
        this.machineIds.add(machineId);
        return true;
    }

    /**
     * 移除一台机器引用。
     *
     * @param machineId 机器 id
     * @return 是否真的移除了
     */
    public boolean removeMachine(UUID machineId) {
        return this.machineIds.remove(machineId);
    }

    /** 给界面和指令用的短 id（前 8 位）。 */
    public String shortId() {
        return this.id.toString().substring(0, 8);
    }

    /**
     * 序列化成 NBT。
     *
     * @param registries 注册表访问（本类不需要，保留签名）
     * @return 新标签
     */
    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(TAG_ID, this.id);
        tag.putString(TAG_NAME, this.name);
        tag.putBoolean(TAG_RUNNING, this.running);
        ListTag refs = new ListTag();
        for (UUID machineId : this.machineIds) {
            CompoundTag ref = new CompoundTag();
            ref.putUUID(TAG_MACHINE_ID, machineId);
            refs.add(ref);
        }
        tag.put(TAG_MACHINE_IDS, refs);
        return tag;
    }

    /**
     * 从 NBT 读回来。
     *
     * @param tag        存档标签
     * @param registries 注册表访问（本类不需要，保留签名）
     * @return 产线；字段缺失时返回 {@code null}
     */
    @Nullable
    public static PassiveLine load(CompoundTag tag, HolderLookup.Provider registries) {
        if (!tag.hasUUID(TAG_ID)) {
            return null;
        }
        PassiveLine line = new PassiveLine(tag.getUUID(TAG_ID), tag.getString(TAG_NAME));
        line.running = tag.getBoolean(TAG_RUNNING);
        ListTag refs = tag.getList(TAG_MACHINE_IDS, Tag.TAG_COMPOUND);
        for (int i = 0; i < refs.size(); i++) {
            CompoundTag ref = refs.getCompound(i);
            if (ref.hasUUID(TAG_MACHINE_ID)) {
                line.machineIds.add(ref.getUUID(TAG_MACHINE_ID));
            }
        }
        return line;
    }
}
