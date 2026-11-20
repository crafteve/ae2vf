package io.github.crafteve.idlefactory.node;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.machine.IoRuleSpec;

import appeng.api.stacks.AEKey;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/**
 * 一侧（输入或输出）的规则清单：同一资源只允许一条，最多 {@link #MAX_RULES_PER_SIDE} 条。
 *
 * <p>清单本身没有身份，身份是节点 id。读档时超限规则丢弃并告警：上限归数据层，不能只靠
 * 写入侧截断，否则界面会显示比协议更多条规则。</p>
 */
public final class NodeIoRules {
    /** 每侧规则数硬上限。改这里等于改线协议，必须同步递增协议版本。 */
    public static final int MAX_RULES_PER_SIDE = 64;

    private final List<IoRuleSpec> specs = new ArrayList<>();

    /** 当前规则（只读）。 */
    public List<IoRuleSpec> all() {
        return List.copyOf(this.specs);
    }

    /** 规则条数。 */
    public int size() {
        return this.specs.size();
    }

    /** 是否为空。 */
    public boolean isEmpty() {
        return this.specs.isEmpty();
    }

    /**
     * 加一条规则。
     *
     * @param spec 规则
     * @return 是否加入；同资源已有规则或已满时返回 {@code false}
     */
    public boolean add(IoRuleSpec spec) {
        if (this.specs.size() >= MAX_RULES_PER_SIDE || this.find(spec.key()) != null) {
            return false;
        }
        this.specs.add(spec);
        return true;
    }

    /**
     * 按资源移除。
     *
     * @param key 资源
     * @return 是否真的移除了
     */
    public boolean remove(AEKey key) {
        return this.specs.removeIf(spec -> spec.key().equals(key));
    }

    /**
     * 按资源替换。
     *
     * @param key         原规则 key
     * @param replacement 新规则；key 必须与原规则一致
     * @return 是否找到并替换
     */
    public boolean replace(AEKey key, IoRuleSpec replacement) {
        if (!replacement.key().equals(key)) {
            return false;
        }
        for (int i = 0; i < this.specs.size(); i++) {
            if (this.specs.get(i).key().equals(key)) {
                this.specs.set(i, replacement);
                return true;
            }
        }
        return false;
    }

    @Nullable
    private IoRuleSpec find(AEKey key) {
        for (IoRuleSpec spec : this.specs) {
            if (spec.key().equals(key)) {
                return spec;
            }
        }
        return null;
    }

    /**
     * 序列化成 NBT 列表。
     *
     * @param registries 注册表访问，物品组件编码需要
     * @return 列表标签
     */
    public ListTag save(HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (IoRuleSpec spec : this.specs) {
            list.add(spec.save(registries));
        }
        return list;
    }

    /**
     * 从 NBT 列表读回来；超限只保留前 {@link #MAX_RULES_PER_SIDE} 条并告警。
     *
     * @param list       列表标签
     * @param registries 注册表访问
     * @param ownerId    节点 id，只用于超限告警
     * @param side       侧名（输入 / 输出），只用于超限告警
     * @return 规则清单
     */
    public static NodeIoRules load(ListTag list, HolderLookup.Provider registries, UUID ownerId,
            String side) {
        NodeIoRules rules = new NodeIoRules();
        int usable = Math.min(list.size(), MAX_RULES_PER_SIDE);
        for (int i = 0; i < usable; i++) {
            IoRuleSpec spec = IoRuleSpec.load(list.getCompound(i), registries);
            if (spec != null) {
                rules.specs.add(spec);
            }
        }
        if (list.size() > MAX_RULES_PER_SIDE) {
            IdleFactory.LOGGER.warn("Idle Factory: 节点 {} 的{}规则超过上限 {}，已丢弃 {} 条",
                    ownerId, side, MAX_RULES_PER_SIDE, list.size() - MAX_RULES_PER_SIDE);
        }
        return rules;
    }
}
