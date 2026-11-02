package io.github.crafteve.idlefactory.factory.machine;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEKey;
import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.machine.ResourceSpec;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * 一台机器的 IO 规则：输入清单和输出清单。
 *
 * <p>规则跟着机器走：删除机器才会丢规则。组本身没有独立 id，机器 id 就是它的身份。</p>
 *
 * <p>同一个资源在同一侧只允许一条规则；输入和输出各自独立。</p>
 */
public final class MachineIoGroup {
    /**
     * 每侧规则上限：输入与输出各自最多多少条。
     *
     * <p>存档解码与写入共用这一个值。它同时是 {@code FactoryTableS2C} 的规则解码上限，
     * 改这里等于改线协议，必须同步递增 {@code ModNetwork.PROTOCOL_VERSION}。</p>
     */
    public static final int MAX_RULES_PER_SIDE = 16;

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
     * 不需要再给 {@code AEKey} 定义一套排序键（物品组件没有天然顺序，按 hash 排会碰撞）。
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
    public boolean removeInput(AEKey key) {
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
    public boolean removeOutput(AEKey key) {
        return this.outputs.removeIf(spec -> spec.key().equals(key));
    }

    /**
     * 按资源替换一条输入规则。
     *
     * @param key         原规则 key
     * @param replacement 新规则；key 必须与原规则一致
     * @return 是否找到并替换
     */
    public boolean replaceInput(AEKey key, ResourceSpec replacement) {
        return replace(this.inputs, key, replacement);
    }

    /**
     * 按资源替换一条输出规则。
     *
     * @param key         原规则 key
     * @param replacement 新规则；key 必须与原规则一致
     * @return 是否找到并替换
     */
    public boolean replaceOutput(AEKey key, ResourceSpec replacement) {
        return replace(this.outputs, key, replacement);
    }

    private static boolean replace(List<ResourceSpec> specs, AEKey key,
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
    private static ResourceSpec find(List<ResourceSpec> specs, AEKey key) {
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
     * @param machineId  机器 id，只用于超限告警
     * @return IO 规则
     */
    public static MachineIoGroup load(CompoundTag tag, HolderLookup.Provider registries,
            UUID machineId) {
        MachineIoGroup group = new MachineIoGroup();
        readSpecs(tag.getList(TAG_INPUTS, Tag.TAG_COMPOUND), registries, group.inputs,
                machineId, "输入");
        readSpecs(tag.getList(TAG_OUTPUTS, Tag.TAG_COMPOUND), registries, group.outputs,
                machineId, "输出");
        return group;
    }

    private static ListTag saveSpecs(HolderLookup.Provider registries, List<ResourceSpec> specs) {
        ListTag list = new ListTag();
        for (ResourceSpec spec : specs) {
            list.add(spec.save(registries));
        }
        return list;
    }

    /**
     * 把 NBT 列表解成规则；超过每侧上限时只保留前 {@link #MAX_RULES_PER_SIDE} 条并告警。
     *
     * <p>超限规则在读档时丢弃并 warn，避免界面显示 N 条却只能操作 16 条：协议写规则时按上限
     * 截断，{@code ruleCount} 却写的是全量，客户端会画出 18 条却只收到 16 条，多出的也删不掉。
     * 上限归数据层，读入就必须守住，不能只靠写入侧。</p>
     *
     * @param list       规则列表
     * @param registries 注册表访问
     * @param target     写入目标
     * @param machineId  机器 id，只用于超限告警
     * @param side       侧名（输入 / 输出），只用于超限告警
     */
    private static void readSpecs(ListTag list, HolderLookup.Provider registries,
            List<ResourceSpec> target, UUID machineId, String side) {
        int usable = Math.min(list.size(), MAX_RULES_PER_SIDE);
        for (int i = 0; i < usable; i++) {
            ResourceSpec spec = ResourceSpec.load(list.getCompound(i), registries);
            if (spec != null) {
                target.add(spec);
            }
        }
        if (list.size() > MAX_RULES_PER_SIDE) {
            IdleFactory.LOGGER.warn("Idle Factory: 机器 {} 的{}规则超过上限 {}，已丢弃 {} 条",
                    machineId, side, MAX_RULES_PER_SIDE, list.size() - MAX_RULES_PER_SIDE);
        }
    }
}
