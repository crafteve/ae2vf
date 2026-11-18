package io.github.crafteve.idlefactory.node;

import java.util.List;
import java.util.UUID;

import io.github.crafteve.idlefactory.machine.ResourceSpec;

import appeng.api.stacks.AEKey;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * 一个节点的 IO 规则：输入与输出两侧的规则清单。
 *
 * <p>规则归节点，堆叠机器共用一套：规则作用于节点内每台在线机器的每个方块，不绑定具体方块。
 * 组本身没有独立 id，节点 id 就是它的身份。</p>
 */
public final class NodeIo {
    /** 每侧规则数硬上限；线协议、存档与写入共用。 */
    public static final int MAX_RULES_PER_SIDE = NodeIoRules.MAX_RULES_PER_SIDE;

    private static final String TAG_INPUTS = "Inputs";
    private static final String TAG_OUTPUTS = "Outputs";

    private NodeIoRules inputs = new NodeIoRules();
    private NodeIoRules outputs = new NodeIoRules();

    /** 输入清单（只读）。 */
    public List<ResourceSpec> inputs() {
        return this.inputs.all();
    }

    /** 输出清单（只读）。 */
    public List<ResourceSpec> outputs() {
        return this.outputs.all();
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
     * @return 是否加入
     */
    public boolean addInput(ResourceSpec spec) {
        return this.inputs.add(spec);
    }

    /**
     * 按资源移除输入规则。
     *
     * @param key 资源
     * @return 是否真的移除了
     */
    public boolean removeInput(AEKey key) {
        return this.inputs.remove(key);
    }

    /**
     * 加一条输出规则。
     *
     * @param spec 规则
     * @return 是否加入
     */
    public boolean addOutput(ResourceSpec spec) {
        return this.outputs.add(spec);
    }

    /**
     * 按资源移除输出规则。
     *
     * @param key 资源
     * @return 是否真的移除了
     */
    public boolean removeOutput(AEKey key) {
        return this.outputs.remove(key);
    }

    /**
     * 按资源替换一条输入规则。
     *
     * @param key         原规则 key
     * @param replacement 新规则
     * @return 是否找到并替换
     */
    public boolean replaceInput(AEKey key, ResourceSpec replacement) {
        return this.inputs.replace(key, replacement);
    }

    /**
     * 按资源替换一条输出规则。
     *
     * @param key         原规则 key
     * @param replacement 新规则
     * @return 是否找到并替换
     */
    public boolean replaceOutput(AEKey key, ResourceSpec replacement) {
        return this.outputs.replace(key, replacement);
    }

    /**
     * 序列化成 NBT。
     *
     * @param registries 注册表访问
     * @return 新标签
     */
    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.put(TAG_INPUTS, this.inputs.save(registries));
        tag.put(TAG_OUTPUTS, this.outputs.save(registries));
        return tag;
    }

    /**
     * 从 NBT 读回来；字段缺失时返回空组。
     *
     * @param tag        存档标签
     * @param registries 注册表访问
     * @param ownerId    节点 id，只用于超限告警
     * @return IO 规则
     */
    public static NodeIo load(CompoundTag tag, HolderLookup.Provider registries, UUID ownerId) {
        NodeIo group = new NodeIo();
        if (tag.contains(TAG_INPUTS, Tag.TAG_LIST)) {
            group.inputs = NodeIoRules.load(tag.getList(TAG_INPUTS, Tag.TAG_COMPOUND), registries,
                    ownerId, "输入");
        }
        if (tag.contains(TAG_OUTPUTS, Tag.TAG_LIST)) {
            group.outputs = NodeIoRules.load(tag.getList(TAG_OUTPUTS, Tag.TAG_COMPOUND), registries,
                    ownerId, "输出");
        }
        return group;
    }
}
