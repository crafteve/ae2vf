package io.github.crafteve.idlefactory.factory.line;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.resource.ResourceKey;
import io.github.crafteve.idlefactory.factory.resource.ResourceSpec;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * 一个 IO 组：一台机器 + 它的输入清单和输出清单。
 *
 * <p>组只记机器库里的机器 id，不重复存坐标。同一个资源在同一侧只允许一条规则；
 * 输入和输出各自独立。</p>
 */
public final class IoGroup {
    private static final String TAG_ID = "Id";
    private static final String TAG_MACHINE = "Machine";
    private static final String TAG_INPUTS = "Inputs";
    private static final String TAG_OUTPUTS = "Outputs";

    private final UUID id;
    private final UUID machineId;
    private final List<ResourceSpec> inputs = new ArrayList<>();
    private final List<ResourceSpec> outputs = new ArrayList<>();

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
    public List<ResourceSpec> inputs() {
        return List.copyOf(this.inputs);
    }

    /** 输出清单（只读）。 */
    public List<ResourceSpec> outputs() {
        return List.copyOf(this.outputs);
    }

    /**
     * 加一条输入规则。
     *
     * @param spec 规则
     * @return 是否加入；同资源已有规则时返回 {@code false}
     */
    public boolean addInput(ResourceSpec spec) {
        if (find(this.inputs, spec.key()) != null) {
            return false;
        }
        this.inputs.add(spec);
        return true;
    }

    /**
     * 按资源移除输入规则。
     *
     * @param key 资源
     * @return 是否真的移除了
     */
    public boolean removeInput(ResourceKey key) {
        return this.inputs.removeIf(spec -> spec.key().equals(key));
    }

    /**
     * 加一条输出规则。
     *
     * @param spec 规则
     * @return 是否加入；同资源已有规则时返回 {@code false}
     */
    public boolean addOutput(ResourceSpec spec) {
        if (find(this.outputs, spec.key()) != null) {
            return false;
        }
        this.outputs.add(spec);
        return true;
    }

    /**
     * 按资源移除输出规则。
     *
     * @param key 资源
     * @return 是否真的移除了
     */
    public boolean removeOutput(ResourceKey key) {
        return this.outputs.removeIf(spec -> spec.key().equals(key));
    }

    /**
     * 按资源替换一条输入规则。
     *
     * <p>界面在配置卡上改面或匹配模式时用；规则的身份仍是 key，只换面/模式，
     * 不改变列表顺序。</p>
     *
     * @param key         原规则 key
     * @param replacement 新规则；key 必须与原规则一致
     * @return 是否找到并替换
     */
    public boolean replaceInput(ResourceKey key, ResourceSpec replacement) {
        return replace(this.inputs, key, replacement);
    }

    /**
     * 按资源替换一条输出规则。
     *
     * @param key         原规则 key
     * @param replacement 新规则；key 必须与原规则一致
     * @return 是否找到并替换
     */
    public boolean replaceOutput(ResourceKey key, ResourceSpec replacement) {
        return replace(this.outputs, key, replacement);
    }

    private static boolean replace(List<ResourceSpec> specs, ResourceKey key,
            ResourceSpec replacement) {
        if (!replacement.key().equals(key)) {
            return false;
        }
        for (int i = 0; i < specs.size(); i++) {
            if (specs.get(i).key().equals(key)) {
                specs.set(i, replacement);
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static ResourceSpec find(List<ResourceSpec> specs, ResourceKey key) {
        for (ResourceSpec spec : specs) {
            if (spec.key().equals(key)) {
                return spec;
            }
        }
        return null;
    }

    /**
     * 序列化成 NBT。
     *
     * @param registries 注册表访问，物品组件编码需要
     * @return 新标签
     */
    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(TAG_ID, this.id);
        tag.putUUID(TAG_MACHINE, this.machineId);
        tag.put(TAG_INPUTS, saveSpecs(registries, this.inputs));
        tag.put(TAG_OUTPUTS, saveSpecs(registries, this.outputs));
        return tag;
    }

    /**
     * 从 NBT 读回来。
     *
     * @param tag        存档标签
     * @param registries 注册表访问，物品组件解码需要
     * @return IO 组；字段缺失时返回 {@code null}
     */
    @Nullable
    public static IoGroup load(CompoundTag tag, HolderLookup.Provider registries) {
        if (!tag.hasUUID(TAG_ID) || !tag.hasUUID(TAG_MACHINE)) {
            return null;
        }
        IoGroup group = new IoGroup(tag.getUUID(TAG_ID), tag.getUUID(TAG_MACHINE));
        readSpecs(tag.getList(TAG_INPUTS, Tag.TAG_COMPOUND), registries, group.inputs);
        readSpecs(tag.getList(TAG_OUTPUTS, Tag.TAG_COMPOUND), registries, group.outputs);
        return group;
    }

    private static ListTag saveSpecs(HolderLookup.Provider registries, List<ResourceSpec> specs) {
        ListTag list = new ListTag();
        for (ResourceSpec spec : specs) {
            list.add(spec.save(registries));
        }
        return list;
    }

    private static void readSpecs(ListTag list, HolderLookup.Provider registries,
            List<ResourceSpec> target) {
        for (int i = 0; i < list.size(); i++) {
            ResourceSpec spec = ResourceSpec.load(list.getCompound(i), registries);
            if (spec != null) {
                target.add(spec);
            }
        }
    }
}
