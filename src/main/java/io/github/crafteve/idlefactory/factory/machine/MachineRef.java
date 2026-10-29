package io.github.crafteve.idlefactory.factory.machine;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.observation.Located;
import io.github.crafteve.idlefactory.factory.links.LinkIds;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * 机器库里的一个目标：一台被工厂接管的机器方块。
 *
 * <p>工厂只记坐标，不持有方块实体引用——方块随时可能被破坏、被替换、被卸载，持有引用会悬空。
 * 真正读写时现按坐标取 {@code BlockEntity}，取不到就当这台机器没了。</p>
 *
 * @param id        目标 id，由维度 + 坐标派生，同一方块永远同一个 id
 * @param dimension 机器所在维度
 * @param pos       机器方块坐标
 */
public record MachineRef(UUID id, ResourceKey<Level> dimension, BlockPos pos) implements Located {

    private static final String TAG_ID = "Id";
    private static final String TAG_DIMENSION = "Dimension";
    private static final String TAG_POS = "Pos";

    /**
     * 按坐标生成目标。id 是确定性的，所以重复登记同一个方块会自然去重。
     *
     * <p>派生规则和容器共用一套（{@link LinkIds}），角色不参与——同一个方块当机器还是
     * 当容器是同一个 id。</p>
     *
     * @param dimension 维度
     * @param pos       方块坐标
     * @return 新目标
     */
    public static MachineRef of(ResourceKey<Level> dimension, BlockPos pos) {
        return new MachineRef(LinkIds.of(dimension, pos), dimension, pos);
    }

    /**
     * 序列化成 NBT。
     *
     * @return 新标签
     */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(TAG_ID, this.id);
        tag.putString(TAG_DIMENSION, this.dimension.location().toString());
        tag.putLong(TAG_POS, this.pos.asLong());
        return tag;
    }

    /**
     * 从 NBT 读回来。
     *
     * @param tag 存档标签
     * @return 目标；字段缺失或维度名非法时返回 {@code null}
     */
    @Nullable
    public static MachineRef load(CompoundTag tag) {
        if (!tag.hasUUID(TAG_ID) || !tag.contains(TAG_DIMENSION) || !tag.contains(TAG_POS)) {
            return null;
        }
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString(TAG_DIMENSION));
        if (dimension == null) {
            return null;
        }
        return new MachineRef(tag.getUUID(TAG_ID),
                ResourceKey.create(Registries.DIMENSION, dimension),
                BlockPos.of(tag.getLong(TAG_POS)));
    }

    /** 给界面和指令用的短 id（前 8 位）。 */
    public String shortId() {
        return this.id.toString().substring(0, 8);
    }
}
