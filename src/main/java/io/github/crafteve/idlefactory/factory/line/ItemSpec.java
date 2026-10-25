package io.github.crafteve.idlefactory.factory.line;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;

/**
 * 一条输入/输出规则：要搬什么、走哪个面。
 *
 * <p>第一版只有物品 id 和面，不存数量——搬运时默认「塞满机器允许的最大值」。
 * {@code face == null} 表示不限面（{@code any}）。面只认绝对六向，不读机器朝向。</p>
 *
 * @param itemId 物品注册名
 * @param face   面；{@code null} 表示不限
 */
public record ItemSpec(String itemId, @Nullable Direction face) {
    /** 不限面的字符串表示。 */
    public static final String ANY = "any";

    private static final String TAG_ITEM = "Item";
    private static final String TAG_FACE = "Face";

    /** 给界面用的面名。 */
    public String faceName() {
        return this.face == null ? ANY : this.face.getName();
    }

    /**
     * 序列化成 NBT。
     *
     * @return 新标签
     */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG_ITEM, this.itemId);
        if (this.face != null) {
            tag.putString(TAG_FACE, this.face.getName());
        }
        return tag;
    }

    /**
     * 从 NBT 读回来。
     *
     * @param tag 存档标签
     * @return 规则；物品 id 缺失时返回 {@code null}
     */
    @Nullable
    public static ItemSpec load(CompoundTag tag) {
        String itemId = tag.getString(TAG_ITEM);
        if (itemId.isEmpty()) {
            return null;
        }
        Direction face = tag.contains(TAG_FACE) ? Direction.byName(tag.getString(TAG_FACE)) : null;
        return new ItemSpec(itemId, face);
    }
}
