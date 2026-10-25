package io.github.crafteve.idlefactory.factory.resource;

import java.util.Locale;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/**
 * 一条输入/输出规则：搬什么、走哪个面、搬多少。
 *
 * <p>{@code amount <= 0} 表示「塞满」：机器能收多少就搬多少。命令目前不暴露数量参数，
 * 所以存量规则都是塞满；字段先留着，等需要限量时再用。</p>
 *
 * <p>面只认绝对六向；{@code face == null} 表示不限面。</p>
 *
 * @param key    资源
 * @param face   面；{@code null} 表示不限
 * @param amount 每轮上限；{@code <= 0} 表示塞满
 */
public record ResourceSpec(ResourceKey key, @Nullable Direction face, long amount) {
    /** 不限面的字符串表示。 */
    public static final String ANY = "any";

    private static final String TAG_KIND = "Kind";
    private static final String TAG_ID = "Id";
    private static final String TAG_FACE = "Face";
    private static final String TAG_AMOUNT = "Amount";

    /** 是否「塞满」。 */
    public boolean fillToCapacity() {
        return this.amount <= 0L;
    }

    /** 给界面用的面名。 */
    public String faceName() {
        return this.face == null ? ANY : this.face.getName();
    }

    /** 给界面和日志用的一行：{@code fluid:minecraft:water[north]}。 */
    public String describe() {
        return this.key.describe() + "[" + faceName() + "]";
    }

    /**
     * 序列化成 NBT。
     *
     * @return 新标签
     */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG_KIND, this.key.kind().name());
        tag.putString(TAG_ID, this.key.id().toString());
        if (this.face != null) {
            tag.putString(TAG_FACE, this.face.getName());
        }
        tag.putLong(TAG_AMOUNT, this.amount);
        return tag;
    }

    /**
     * 从 NBT 读回来。
     *
     * <p>只认新格式；旧版只有 {@code Item/Face}，读不出来就丢弃这条规则——当前阶段不兼容旧存档。</p>
     *
     * @param tag 存档标签
     * @return 规则；格式不对时返回 {@code null}
     */
    @Nullable
    public static ResourceSpec load(CompoundTag tag) {
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
        return new ResourceSpec(new ResourceKey(kind, id), face, tag.getLong(TAG_AMOUNT));
    }
}
