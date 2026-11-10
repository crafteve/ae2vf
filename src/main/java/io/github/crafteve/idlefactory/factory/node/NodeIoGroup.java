package io.github.crafteve.idlefactory.factory.node;

import java.util.ArrayList;
import java.util.List;
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
 * 一个节点的 IO 规则：输入清单和输出清单。
 *
 * <p>规则归节点，堆叠机器共用一套：规则作用于节点内每台在线机器的每个方块，不绑定具体方块。
 * 组本身没有独立 id，节点 id 就是它的身份。</p>
 *
 * <p>同一个资源在同一侧只允许一条规则；输入和输出各自独立。</p>
 */
public final class NodeIoGroup {
    /**
     * 每侧规则数硬上限：输入与输出各自最多多少条。
     *
     * <p>存档解码、网络解码与写入共用这一个值；config 只能把它调低，不能调高。
     * 改这里等于改线协议，必须同步递增 {@code ModNetwork.PROTOCOL_VERSION}。</p>
     */
    public static final int MAX_RULES_PER_SIDE = 64;

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
     * @param ownerId    节点 id，只用于超限告警
     * @return IO 规则
     */
    public static NodeIoGroup load(CompoundTag tag, HolderLookup.Provider registries,
            UUID ownerId) {
        NodeIoGroup group = new NodeIoGroup();
        readSpecs(tag.getList(TAG_INPUTS, Tag.TAG_COMPOUND), registries, group.inputs,
                ownerId, "输入");
        readSpecs(tag.getList(TAG_OUTPUTS, Tag.TAG_COMPOUND), registries, group.outputs,
                ownerId, "输出");
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
     * @param ownerId    节点 id，只用于超限告警
     * @param side       侧名（输入 / 输出），只用于超限告警
     */
    private static void readSpecs(ListTag list, HolderLookup.Provider registries,
            List<ResourceSpec> target, UUID ownerId, String side) {
        int usable = Math.min(list.size(), MAX_RULES_PER_SIDE);
        for (int i = 0; i < usable; i++) {
            ResourceSpec spec = ResourceSpec.load(list.getCompound(i), registries);
            if (spec != null) {
                target.add(spec);
            }
        }
        if (list.size() > MAX_RULES_PER_SIDE) {
            IdleFactory.LOGGER.warn("Idle Factory: 节点 {} 的{}规则超过上限 {}，已丢弃 {} 条",
                    ownerId, side, MAX_RULES_PER_SIDE, list.size() - MAX_RULES_PER_SIDE);
        }
    }
}
