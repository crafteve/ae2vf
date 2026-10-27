package io.github.crafteve.idlefactory.factory.resource;

import java.util.Locale;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;

import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * 一条输入/输出规则：搬什么、走哪个面、搬多少、怎么匹配组件。
 *
 * <p>{@code amount <= 0} 表示「塞满」：机器能收多少就搬多少。命令目前不暴露数量参数，
 * 所以存量规则都是塞满；字段先留着，等需要限量时再用。</p>
 *
 * <p>面只认绝对六向；{@code face == null} 表示不限面。</p>
 *
 * <p>匹配模式只对物品有意义：没有组件的规则默认 {@link MatchMode#IGNORE_COMPONENTS}，
 * 带组件的规则一定是 {@link MatchMode#EXACT}。旧存档没有 {@code Mode} 字段，按模糊读入，
 * 保持老版本「按 id 搬」的语义。</p>
 *
 * @param key    资源
 * @param face   面；{@code null} 表示不限
 * @param amount 每轮上限；{@code <= 0} 表示塞满
 * @param mode   物品匹配模式；流体/能量固定精确
 */
public record ResourceSpec(ResourceKey key, @Nullable Direction face, long amount, MatchMode mode) {
    /** 不限面的字符串表示。 */
    public static final String ANY = "any";

    private static final String TAG_KIND = "Kind";
    private static final String TAG_ID = "Id";
    private static final String TAG_FACE = "Face";
    private static final String TAG_AMOUNT = "Amount";
    private static final String TAG_MODE = "Mode";
    private static final String TAG_STACK = "Stack";

    /**
     * 不带模式的便捷构造：由 key 是否带组件推导默认模式。
     *
     * @param key    资源
     * @param face   面
     * @param amount 每轮上限
     */
    public ResourceSpec(ResourceKey key, @Nullable Direction face, long amount) {
        this(key, face, amount, defaultMode(key));
    }

    /**
     * 规范化模式与 key 的关系。
     *
     * <p>流体/能量没有组件语义，一律精确；「模糊 + 带组件 key」这种矛盾输入按精确处理，
     * 组件才是玩家真正写下来的意图。</p>
     */
    public ResourceSpec {
        Objects.requireNonNull(key, "key");
        if (key.kind() != ResourceKind.ITEM) {
            mode = MatchMode.EXACT;
        } else if (mode == null) {
            mode = defaultMode(key);
        }
        if (mode == MatchMode.IGNORE_COMPONENTS && key.hasComponents()) {
            mode = MatchMode.EXACT;
        }
    }

    private static MatchMode defaultMode(ResourceKey key) {
        if (key.kind() != ResourceKind.ITEM) {
            return MatchMode.EXACT;
        }
        return key.hasComponents() ? MatchMode.EXACT : MatchMode.IGNORE_COMPONENTS;
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

    /** 给界面和日志用的一行：{@code fluid:minecraft:water[north]}；模糊规则追加 {@code [模糊]}。 */
    public String describe() {
        String match = this.mode == MatchMode.IGNORE_COMPONENTS ? " [模糊]" : "";
        return this.key.describe() + match + "[" + faceName() + "]";
    }

    /**
     * 序列化成 NBT。
     *
     * <p>只有带组件的物品规则才写 {@code Stack}；普通物品/流体/能量仍然只写
     * {@code Kind + Id}，和旧档结构一致。{@code Mode} 会一直写，读档时缺省视为模糊。</p>
     *
     * @param registries 注册表访问，组件里的附魔/药水需要它才能编码
     * @return 新标签
     */
    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG_KIND, this.key.kind().name());
        tag.putString(TAG_ID, this.key.id().toString());
        if (this.key.kind() == ResourceKind.ITEM) {
            tag.putString(TAG_MODE, this.mode.name());
        }
        if (this.key.hasComponents()) {
            tag.put(TAG_STACK, encodeStack(registries, this.key.toStack()));
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
     * <p>只认新格式；旧版只有 {@code Item/Face}，读不出来就丢弃这条规则——当前阶段不兼容旧存档。
     * 有 {@code Stack} 的规则一律精确匹配；没有 {@code Mode} 的物品规则按模糊读入。</p>
     *
     * @param tag        存档标签
     * @param registries 注册表访问
     * @return 规则；格式不对或组件解码失败时返回 {@code null}
     */
    @Nullable
    public static ResourceSpec load(CompoundTag tag, HolderLookup.Provider registries) {
        String kindName = tag.getString(TAG_KIND);
        if (kindName.isEmpty()) {
            return null;
        }
        ResourceKind kind;
        try {
            kind = ResourceKind.valueOf(kindName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(tag.getString(TAG_ID));
        if (id == null) {
            return null;
        }
        Direction face = tag.contains(TAG_FACE) ? Direction.byName(tag.getString(TAG_FACE)) : null;
        long amount = tag.getLong(TAG_AMOUNT);

        if (kind == ResourceKind.ITEM && tag.contains(TAG_STACK, Tag.TAG_COMPOUND)) {
            ItemStack stack = decodeStack(registries, tag.getCompound(TAG_STACK));
            if (stack == null || stack.isEmpty()) {
                IdleFactory.LOGGER.warn("IdleFactory: 物品规则组件解码失败，丢弃规则 {}", id);
                return null;
            }
            return new ResourceSpec(ResourceKey.item(stack), face, amount, MatchMode.EXACT);
        }

        MatchMode mode = readMode(tag, kind);
        return new ResourceSpec(ResourceKey.of(kind, id), face, amount, mode);
    }

    private static MatchMode readMode(CompoundTag tag, ResourceKind kind) {
        MatchMode fallback = kind == ResourceKind.ITEM
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

    private static Tag encodeStack(HolderLookup.Provider registries, ItemStack stack) {
        RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        return ItemStack.CODEC.encodeStart(ops, stack).getOrThrow();
    }

    @Nullable
    private static ItemStack decodeStack(HolderLookup.Provider registries, CompoundTag tag) {
        RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        return ItemStack.CODEC.parse(ops, tag).result().orElse(null);
    }
}
