package io.github.crafteve.idlefactory.factory.machine;

import java.util.Locale;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;

/**
 * 一条输入/输出规则：搬什么、走哪个面、搬多少、怎么匹配组件。
 *
 * <p>资源身份直接用 AE2 的 {@link AEKey}：物品是 {@link AEItemKey}（带完整数据组件），
 * 流体是 AE2 的流体 key。装了 Applied Flux 时能量 key 也是普通 {@code AEKey}，
 * 所以本类不再有「类型枚举」字段，类型判断一律用 {@code instanceof}。</p>
 *
 * <p>{@code amount <= 0} 表示「塞满」：机器能收多少就搬多少。命令目前不暴露数量参数，
 * 所以存量规则都是塞满；字段先留着，等需要限量时再用。</p>
 *
 * <p>面只认绝对六向；{@code face == null} 表示不限面。</p>
 *
 * <p>匹配模式只对物品有意义：没有自定义组件的规则默认 {@link MatchMode#IGNORE_COMPONENTS}，
 * 带组件快照的规则默认 {@link MatchMode#EXACT}，两者都可以显式切换。</p>
 *
 * @param key    资源
 * @param face   面；{@code null} 表示不限
 * @param amount 每轮上限；{@code <= 0} 表示塞满
 * @param mode   物品匹配模式；非物品固定精确
 */
public record ResourceSpec(AEKey key, @Nullable Direction face, long amount, MatchMode mode) {
    /** 不限面的字符串表示。 */
    public static final String ANY = "any";

    private static final String TAG_KEY = "Key";
    private static final String TAG_FACE = "Face";
    private static final String TAG_AMOUNT = "Amount";
    private static final String TAG_MODE = "Mode";

    /**
     * 不带模式的便捷构造：由 key 是否带自定义组件推导默认模式。
     *
     * @param key    资源
     * @param face   面
     * @param amount 每轮上限
     */
    public ResourceSpec(AEKey key, @Nullable Direction face, long amount) {
        this(key, face, amount, defaultMode(key));
    }

    /**
     * 规范化模式与 key 的关系。
     *
     * <p>非物品没有组件语义，一律精确；物品保留调用方给出的模式，未给出时按 key 是否
     * 带自定义组件取默认。带组件的 key 也可以显式使用
     * {@link MatchMode#IGNORE_COMPONENTS}，表示只按注册名匹配所有变体。</p>
     */
    public ResourceSpec {
        Objects.requireNonNull(key, "key");
        if (!(key instanceof AEItemKey)) {
            mode = MatchMode.EXACT;
        } else if (mode == null) {
            mode = defaultMode(key);
        }
    }

    /**
     * 由物品是否带自定义组件推导默认匹配模式。
     *
     * <p>和默认组件比较，相同就不算自定义——这样「普通铁锭」按模糊匹配、附魔改名的铁锭
     * 按精确匹配，行为和旧版 {@code ResourceKey} 一致。</p>
     *
     * @param key 资源
     * @return 默认匹配模式
     */
    private static MatchMode defaultMode(AEKey key) {
        if (!(key instanceof AEItemKey itemKey)) {
            return MatchMode.EXACT;
        }
        ItemStack stack = itemKey.getReadOnlyStack();
        boolean custom = !ItemStack.isSameItemSameComponents(stack, new ItemStack(stack.getItem()));
        return custom ? MatchMode.EXACT : MatchMode.IGNORE_COMPONENTS;
    }

    /** 是否「塞满」。 */
    public boolean fillToCapacity() {
        return this.amount <= 0L;
    }

    /** 给界面用的面名。 */
    public String faceName() {
        return this.face == null ? ANY : this.face.getName();
    }

    /**
     * 是否忽略组件匹配。
     *
     * @return 物品模糊规则时为 {@code true}
     */
    public boolean fuzzy() {
        return this.mode == MatchMode.IGNORE_COMPONENTS;
    }

    /** 给界面和日志用的一行：{@code 水桶[north]}；模糊规则追加 {@code [模糊]}。 */
    public String describe() {
        String match = this.mode == MatchMode.IGNORE_COMPONENTS ? " [模糊]" : "";
        return this.key.getDisplayName().getString() + match + "[" + faceName() + "]";
    }

    /**
     * 序列化成 NBT。
     *
     * <p>整个 key 交给 AE2 的 {@link AEKey#toTagGeneric} 编码，不再自己拆类型 + 注册名 +
     * 组件快照。{@code Mode} 只对物品写；读档时缺省按模糊。</p>
     *
     * @param registries 注册表访问，组件里的附魔 / 药水需要它才能编码
     * @return 新标签
     */
    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.put(TAG_KEY, this.key.toTagGeneric(registries));
        if (this.key instanceof AEItemKey) {
            tag.putString(TAG_MODE, this.mode.name());
        }
        if (this.face != null) {
            tag.putString(TAG_FACE, this.face.getName());
        }
        tag.putLong(TAG_AMOUNT, this.amount);
        return tag;
    }

    /**
     * 从 NBT 读回来。
     *
     * <p>只认新格式；key 解码失败就丢弃这条规则——当前阶段不兼容旧存档。</p>
     *
     * @param tag        存档标签
     * @param registries 注册表访问
     * @return 规则；格式不对或 key 解码失败时返回 {@code null}
     */
    @Nullable
    public static ResourceSpec load(CompoundTag tag, HolderLookup.Provider registries) {
        if (!tag.contains(TAG_KEY, Tag.TAG_COMPOUND)) {
            return null;
        }
        AEKey key;
        try {
            key = AEKey.fromTagGeneric(registries, tag.getCompound(TAG_KEY));
        } catch (RuntimeException e) {
            // 注册表里没有这个 id（模组被移除）或组件解码失败
            return null;
        }
        if (key == null) {
            return null;
        }
        Direction face = tag.contains(TAG_FACE) ? Direction.byName(tag.getString(TAG_FACE)) : null;
        long amount = tag.getLong(TAG_AMOUNT);
        return new ResourceSpec(key, face, amount, readMode(tag, key));
    }

    /**
     * 读匹配模式。
     *
     * @param tag 存档标签
     * @param key 已解码的资源
     * @return 模式；缺字段时物品按模糊、其他按精确
     */
    private static MatchMode readMode(CompoundTag tag, AEKey key) {
        MatchMode fallback = key instanceof AEItemKey
                ? MatchMode.IGNORE_COMPONENTS : MatchMode.EXACT;
        if (!tag.contains(TAG_MODE)) {
            return fallback;
        }
        try {
            return MatchMode.valueOf(tag.getString(TAG_MODE).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
