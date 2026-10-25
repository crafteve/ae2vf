package io.github.crafteve.idlefactory.factory.line;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * 一个 IO 组：一台机器 + 它的输入清单和输出清单。
 *
 * <p>组只记机器库里的机器 id，不重复存坐标。同一个物品在同一侧只允许一条规则；
 * 输入和输出各自独立。</p>
 */
public final class IoGroup {
    private static final String TAG_ID = "Id";
    private static final String TAG_MACHINE = "Machine";
    private static final String TAG_INPUTS = "Inputs";
    private static final String TAG_OUTPUTS = "Outputs";

    private final UUID id;
    private final UUID machineId;
    private final List<ItemSpec> inputs = new ArrayList<>();
    private final List<ItemSpec> outputs = new ArrayList<>();

    /**
     * @param id        组 id
     * @param machineId 机器库里的机器 id
     */
    public IoGroup(UUID id, UUID machineId) {
        this.id = id;
        this.machineId = machineId;
    }

    /** 组 id。 */
    public UUID id() {
        return this.id;
    }

    /** 机器库里的机器 id。 */
    public UUID machineId() {
        return this.machineId;
    }

    /** 输入清单（只读）。 */
    public List<ItemSpec> inputs() {
        return List.copyOf(this.inputs);
    }

    /** 输出清单（只读）。 */
    public List<ItemSpec> outputs() {
        return List.copyOf(this.outputs);
    }

    /**
     * 加一条输入规则。
     *
     * @param spec 规则
     * @return 是否加入；同物品已有规则时返回 {@code false}
     */
    public boolean addInput(ItemSpec spec) {
        if (find(this.inputs, spec.itemId()) != null) {
            return false;
        }
        this.inputs.add(spec);
        return true;
    }

    /**
     * 按物品 id 移除输入规则。
     *
     * @param itemId 物品注册名
     * @return 是否真的移除了
     */
    public boolean removeInput(String itemId) {
        return this.inputs.removeIf(spec -> spec.itemId().equals(itemId));
    }

    /**
     * 加一条输出规则。
     *
     * @param spec 规则
     * @return 是否加入；同物品已有规则时返回 {@code false}
     */
    public boolean addOutput(ItemSpec spec) {
        if (find(this.outputs, spec.itemId()) != null) {
            return false;
        }
        this.outputs.add(spec);
        return true;
    }

    /**
     * 按物品 id 移除输出规则。
     *
     * @param itemId 物品注册名
     * @return 是否真的移除了
     */
    public boolean removeOutput(String itemId) {
        return this.outputs.removeIf(spec -> spec.itemId().equals(itemId));
    }

    @Nullable
    private static ItemSpec find(List<ItemSpec> specs, String itemId) {
        for (ItemSpec spec : specs) {
            if (spec.itemId().equals(itemId)) {
                return spec;
            }
        }
        return null;
    }

    /**
     * 序列化成 NBT。
     *
     * @return 新标签
     */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(TAG_ID, this.id);
        tag.putUUID(TAG_MACHINE, this.machineId);
        tag.put(TAG_INPUTS, saveSpecs(this.inputs));
        tag.put(TAG_OUTPUTS, saveSpecs(this.outputs));
        return tag;
    }

    /**
     * 从 NBT 读回来。
     *
     * @param tag 存档标签
     * @return IO 组；字段缺失时返回 {@code null}
     */
    @Nullable
    public static IoGroup load(CompoundTag tag) {
        if (!tag.hasUUID(TAG_ID) || !tag.hasUUID(TAG_MACHINE)) {
            return null;
        }
        IoGroup group = new IoGroup(tag.getUUID(TAG_ID), tag.getUUID(TAG_MACHINE));
        readSpecs(tag.getList(TAG_INPUTS, Tag.TAG_COMPOUND), group.inputs);
        readSpecs(tag.getList(TAG_OUTPUTS, Tag.TAG_COMPOUND), group.outputs);
        return group;
    }

    private static ListTag saveSpecs(List<ItemSpec> specs) {
        ListTag list = new ListTag();
        for (ItemSpec spec : specs) {
            list.add(spec.save());
        }
        return list;
    }

    private static void readSpecs(ListTag list, List<ItemSpec> target) {
        for (int i = 0; i < list.size(); i++) {
            ItemSpec spec = ItemSpec.load(list.getCompound(i));
            if (spec != null) {
                target.add(spec);
            }
        }
    }
}
