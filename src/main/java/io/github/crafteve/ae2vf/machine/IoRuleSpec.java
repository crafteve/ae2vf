package io.github.crafteve.ae2vf.machine;

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
public record IoRuleSpec(AEKey key, @Nullable Direction face, long amount, MatchMode mode) {
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
    public IoRuleSpec(AEKey key, @Nullable Direction face, long amount) {
        this(key, face, amount, defaultMode(key));
    }

    /**
     * 规范化模式与 key 的关系。
     *
     * <p>非物品没有组件语义，一律精确；物品保留调用方给出的模式，未给出时按 key 是否
     * 带自定义组件取默认。带组件的 key 也可以显式使用
     * {@link MatchMode#IGNORE_COMPONENTS}，表示只按注册名匹配所有变体。</p>
     */
    public IoRuleSpec {
        Objects.requireNonNull(key, "key");
        if (!(key instanceof AEItemKey)) {
            mode = MatchMode.EXACT;
        } else if (mode == null) {
            mode = defaultMode(key);
        }
    }

    /**
     * 新规则的默认匹配模式：一律精确。
     *
     * <p>模糊匹配必须由玩家在规则上显式切换；旧存档缺字段时仍按模糊读入，见
     * {@code readMode}。</p>
     *
     * @param key 资源
     * @return 默认匹配模式
     */
    private static MatchMode defaultMode(AEKey key) {
        // 新规则一律精确：模糊会跨组件匹配所有变体，必须由玩家显式切换。
        return MatchMode.EXACT;
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

    /**
     * 给界面、日志和指令用的一行：{@code 铁锭[minecraft:iron_ingot][any]}。
     *
     * <p>带资源 id 是为了排查：同名不同组件的物品显示名一样，只有 id 加组件标记才能看出
     * 它们是两个不同的 key。模糊规则追加 {@code [模糊]}，带自定义组件的物品追加
     * {@code [组件]}。</p>
     */
    public String describe() {
        String match = this.mode == MatchMode.IGNORE_COMPONENTS ? " [模糊]" : "";
        String components = this.key instanceof AEItemKey itemKey
                && !itemKey.getReadOnlyStack().getComponents().isEmpty() ? " [组件]" : "";
        return this.key.getDisplayName().getString()
                + "[" + this.key.getId() + "]" + components + match + "[" + faceName() + "]";
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
    public static IoRuleSpec load(CompoundTag tag, HolderLookup.Provider registries) {
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
        return new IoRuleSpec(key, face, amount, readMode(tag, key));
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
