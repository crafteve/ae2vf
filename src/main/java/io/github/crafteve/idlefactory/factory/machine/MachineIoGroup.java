package io.github.crafteve.idlefactory.factory.machine;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.resource.ResourceKey;
import io.github.crafteve.idlefactory.factory.resource.ResourceSpec;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * 一台机器的 IO 规则：输入清单和输出清单。
 *
 * <p>规则跟着机器走，不跟着产线走；从产线移除机器不会丢规则。组本身没有独立 id，
 * 机器 id 就是它的身份。</p>
 *
 * <p>同一个资源在同一侧只允许一条规则；输入和输出各自独立。</p>
 */
public final class MachineIoGroup {
    private static final String TAG_INPUTS = "Inputs";
    private static final String TAG_OUTPUTS = "Outputs";

    private final List<ResourceSpec> inputs = new ArrayList<>();
    private final List<ResourceSpec> outputs = new ArrayList<>();

    /** 输入清单（只读）。 */
    public List<ResourceSpec> inputs() {
        return List.copyOf(this.inputs);
    }

    /** 输出清单（只读）。 */
    public List<ResourceSpec> outputs() {
        return List.copyOf(this.outputs);
    }

    /** 输入 + 输出规则总数。 */
    public int ruleCount() {
        return this.inputs.size() + this.outputs.size();
    }

    /** 是否有任何规则。 */
    public boolean isEmpty() {
        return this.inputs.isEmpty() && this.outputs.isEmpty();
    }

    /**
     * 这台机器规则的签名：两侧规则的集合。
     *
     * <p>签名用来判断两台机器能不能并成界面上的同一组。这里用集合而不是「排序后的列表」，
     * 是因为集合相等与规则登记顺序无关；同时 {@code Set.hashCode} 能直接当分组键，
     * 不需要再给 {@code ResourceKey} 定义一套排序键（物品组件没有天然顺序，按 hash 排会碰撞）。
     * 同一侧同一资源只允许一条规则，所以集合不会丢掉规则数量。</p>
     *
     * @return 签名；没有规则时两侧都是空集
     */
    public Signature signature() {
        return new Signature(Set.copyOf(this.inputs), Set.copyOf(this.outputs));
    }

    /**
     * 把另一台机器的规则整体照抄过来，先清空自己。
     *
     * <p>扩容时用：新成员加入一个已经有规则的组，先把它套成本组锚点的规则，才能继续算同组。
     * 调用方负责决定要不要复制，本方法不做任何判断。</p>
     *
     * @param source 源机器规则
     */
    public void copyFrom(MachineIoGroup source) {
        this.inputs.clear();
        this.inputs.addAll(source.inputs);
        this.outputs.clear();
        this.outputs.addAll(source.outputs);
    }

    /**
     * 一组 IO 规则的签名。
     *
     * <p>只活在内存里：不落盘、不进网络包。客户端拿到快照后会从两侧规则列表现场构造。</p>
     *
     * @param inputs  输入规则集合
     * @param outputs 输出规则集合
     */
    public record Signature(Set<ResourceSpec> inputs, Set<ResourceSpec> outputs) {
        public Signature {
            inputs = Set.copyOf(inputs);
            outputs = Set.copyOf(outputs);
        }
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
        tag.put(TAG_INPUTS, saveSpecs(registries, this.inputs));
        tag.put(TAG_OUTPUTS, saveSpecs(registries, this.outputs));
        return tag;
    }

    /**
     * 从 NBT 读回来；字段缺失时返回空组。
     *
     * @param tag        存档标签
     * @param registries 注册表访问，物品组件解码需要
     * @return IO 规则
     */
    public static MachineIoGroup load(CompoundTag tag, HolderLookup.Provider registries) {
        MachineIoGroup group = new MachineIoGroup();
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
