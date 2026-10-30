package io.github.crafteve.idlefactory.factory.line;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * 一条被动产线：一组机器组的引用。
 *
 * <p>产线不再持有 IO 规则；机器自己带规则。从产线移除机器只是删引用，机器和规则都留在机器库。</p>
 *
 * <p>产线现在存两层：{@link StackEntry} 的列表，每项是一个界面上的堆叠行。顺序就是面板顺序，
 * 数量就是组内 machineId 的数量，两者都落盘，下次打开界面看到的是用户调过的样子。
 * 机器库那边仍然按「同方块、同 IO」自动合并，但那是只读展示，不写在这里。</p>
 */
public final class PassiveLine {
    private static final String TAG_ID = "Id";
    private static final String TAG_NAME = "Name";
    private static final String TAG_RUNNING = "Running";
    private static final String TAG_STACKS = "Stacks";
    private static final String TAG_STACK_ID = "StackId";
    private static final String TAG_MACHINES = "Machines";
    private static final String TAG_MACHINE_ID = "Id";
    /** 旧档字段：平铺的机器 id；读入时每台各转成一个组。 */
    private static final String TAG_LEGACY_MACHINE_IDS = "MachineIds";

    private final UUID id;
    private String name;
    private boolean running;
    private final List<StackEntry> stacks = new ArrayList<>();

    /**
     * 一个界面堆叠：共享同一个 {@code stackId} 的机器。
     *
     * @param stackId    组 id，界面顺序的稳定标识
     * @param machineIds 组内机器，顺序即组内顺序
     */
    public record StackEntry(UUID stackId, List<UUID> machineIds) {
        public StackEntry {
            machineIds = List.copyOf(machineIds);
        }
    }

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

    /** 所有组（只读，保留面板顺序）。 */
    public List<StackEntry> stacks() {
        return List.copyOf(this.stacks);
    }

    /** 组数，也就是界面上的行数。 */
    public int stackCount() {
        return this.stacks.size();
    }

    /** 产线里的机器 id（只读，按组顺序展开）。 */
    public List<UUID> machineIds() {
        List<UUID> ids = new ArrayList<>();
        for (StackEntry stack : this.stacks) {
            ids.addAll(stack.machineIds());
        }
        return ids;
    }

    /** 机器数（所有组的总和）。 */
    public int machineCount() {
        int count = 0;
        for (StackEntry stack : this.stacks) {
            count += stack.machineIds().size();
        }
        return count;
    }

    /** 是否包含这台机器。 */
    public boolean containsMachine(UUID machineId) {
        return this.stackOf(machineId).isPresent();
    }

    /**
     * 机器属于哪个组。
     *
     * @param machineId 机器 id
     * @return 组；不在产线时为空
     */
    public Optional<UUID> stackOf(UUID machineId) {
        for (StackEntry stack : this.stacks) {
            if (stack.machineIds().contains(machineId)) {
                return Optional.of(stack.stackId());
            }
        }
        return Optional.empty();
    }

    /**
     * 组在面板里的序号。
     *
     * @param stackId 组 id
     * @return 序号；组不存在时为 -1
     */
    public int stackIndex(UUID stackId) {
        for (int i = 0; i < this.stacks.size(); i++) {
            if (this.stacks.get(i).stackId().equals(stackId)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 把机器加进一个新组，追加到面板末尾。
     *
     * @param machineId 机器 id
     * @return 是否加入；已在任何组里时返回 {@code false}
     */
    public boolean addMachine(UUID machineId) {
        if (this.containsMachine(machineId)) {
            return false;
        }
        List<UUID> members = new ArrayList<>();
        members.add(machineId);
        this.stacks.add(new StackEntry(UUID.randomUUID(), members));
        return true;
    }

    /**
     * 把机器加进指定组；组不存在时新建并追加到末尾。
     *
     * @param machineId 机器 id
     * @param stackId   目标组 id；{@code null} 表示新开一组
     * @return 实际加入的组 id；已经在别的组时返回 {@code null}
     */
    @Nullable
    public UUID addMachine(UUID machineId, @Nullable UUID stackId) {
        if (this.containsMachine(machineId)) {
            return null;
        }
        UUID target = stackId;
        if (target != null) {
            for (int i = 0; i < this.stacks.size(); i++) {
                StackEntry stack = this.stacks.get(i);
                if (!stack.stackId().equals(target)) {
                    continue;
                }
                List<UUID> members = new ArrayList<>(stack.machineIds());
                members.add(machineId);
                this.stacks.set(i, new StackEntry(stack.stackId(), members));
                return stack.stackId();
            }
        }
        target = target == null ? UUID.randomUUID() : target;
        List<UUID> members = new ArrayList<>();
        members.add(machineId);
        this.stacks.add(new StackEntry(target, members));
        return target;
    }

    /**
     * 移除一台机器；组空了就删掉这一行。
     *
     * @param machineId 机器 id
     * @return 是否真的移除了
     */
    public boolean removeMachine(UUID machineId) {
        for (int i = 0; i < this.stacks.size(); i++) {
            StackEntry stack = this.stacks.get(i);
            if (!stack.machineIds().contains(machineId)) {
                continue;
            }
            List<UUID> members = new ArrayList<>(stack.machineIds());
            members.remove(machineId);
            if (members.isEmpty()) {
                this.stacks.remove(i);
            } else {
                this.stacks.set(i, new StackEntry(stack.stackId(), members));
            }
            return true;
        }
        return false;
    }

    /**
     * 移除整组。
     *
     * @param stackId 组 id
     * @return 是否真的移除了
     */
    public boolean removeStack(UUID stackId) {
        return this.stacks.removeIf(stack -> stack.stackId().equals(stackId));
    }

    /**
     * 复制一份，只保留 {@code keep} 为真的成员，空组丢弃。
     *
     * <p>读档时用：存档里可能引用了已经不存在的机器，或者一台机器被两条产线引用。
     * 过滤必须逐台做，但**不能重建组**——否则 5 台一组会被拆成 5 行。</p>
     *
     * @param keep 成员保留判断
     * @return 新产线；id、名字、运行状态、组结构都保留
     */
    public PassiveLine copyFiltered(Filter keep) {
        PassiveLine copy = new PassiveLine(this.id, this.name);
        copy.running = this.running;
        for (StackEntry stack : this.stacks) {
            List<UUID> members = new ArrayList<>();
            for (UUID machineId : stack.machineIds()) {
                if (keep.test(machineId)) {
                    members.add(machineId);
                }
            }
            if (!members.isEmpty()) {
                copy.stacks.add(new StackEntry(stack.stackId(), members));
            }
        }
        return copy;
    }

    /** 成员过滤判断。 */
    @FunctionalInterface
    public interface Filter {
        /**
         * @param machineId 机器 id
         * @return 是否保留
         */
        boolean test(UUID machineId);
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
        ListTag stackList = new ListTag();
        for (StackEntry stack : this.stacks) {
            CompoundTag stackTag = new CompoundTag();
            stackTag.putUUID(TAG_STACK_ID, stack.stackId());
            ListTag machineList = new ListTag();
            for (UUID machineId : stack.machineIds()) {
                CompoundTag ref = new CompoundTag();
                ref.putUUID(TAG_MACHINE_ID, machineId);
                machineList.add(ref);
            }
            stackTag.put(TAG_MACHINES, machineList);
            stackList.add(stackTag);
        }
        tag.put(TAG_STACKS, stackList);
        return tag;
    }

    /**
     * 从 NBT 读回来；旧档的平铺 {@code MachineIds} 每台转成一个组。
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
        if (tag.contains(TAG_STACKS, Tag.TAG_LIST)) {
            readStacks(tag.getList(TAG_STACKS, Tag.TAG_COMPOUND), line.stacks);
        } else {
            readLegacyMachines(tag.getList(TAG_LEGACY_MACHINE_IDS, Tag.TAG_COMPOUND), line.stacks);
        }
        return line;
    }

    private static void readStacks(ListTag stackList, List<StackEntry> target) {
        for (int i = 0; i < stackList.size(); i++) {
            CompoundTag stackTag = stackList.getCompound(i);
            if (!stackTag.hasUUID(TAG_STACK_ID)) {
                continue;
            }
            UUID stackId = stackTag.getUUID(TAG_STACK_ID);
            ListTag machineList = stackTag.getList(TAG_MACHINES, Tag.TAG_COMPOUND);
            List<UUID> members = new ArrayList<>();
            for (int j = 0; j < machineList.size(); j++) {
                CompoundTag ref = machineList.getCompound(j);
                if (ref.hasUUID(TAG_MACHINE_ID)) {
                    members.add(ref.getUUID(TAG_MACHINE_ID));
                }
            }
            if (!members.isEmpty()) {
                target.add(new StackEntry(stackId, members));
            }
        }
    }

    /**
     * 旧档迁移：原来是一串机器，现在每台各成一行。
     *
     * @param machineList 旧 {@code MachineIds}
     * @param target      目标组列表
     */
    private static void readLegacyMachines(ListTag machineList, List<StackEntry> target) {
        for (int i = 0; i < machineList.size(); i++) {
            CompoundTag ref = machineList.getCompound(i);
            if (!ref.hasUUID(TAG_MACHINE_ID)) {
                continue;
            }
            List<UUID> members = new ArrayList<>();
            members.add(ref.getUUID(TAG_MACHINE_ID));
            target.add(new StackEntry(UUID.randomUUID(), members));
        }
    }
}
