package io.github.crafteve.idlefactory.factory.location;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 方块位置键：维度 + 坐标。
 *
 * <p>它是全项目唯一的「位置」身份——观测、能力缓存、面表、占用表、世界事件桥都用它当键。
 * 放在最底层，任何包都可以向下依赖它，它不依赖任何 factory 子包。</p>
 *
 * <p>构造时把坐标转成不可变副本；两个字段一起决定相等与哈希，可以直接当 Map 的键。</p>
 *
 * @param dimension 所在维度
 * @param pos       方块坐标
 */
public record BlockKey(ResourceKey<Level> dimension, BlockPos pos) {

    public BlockKey {
        pos = pos.immutable();
    }

    /**
     * 按维度和坐标建键。
     *
     * @param dimension 所在维度
     * @param pos       方块坐标
     * @return 位置键
     */
    public static BlockKey of(ResourceKey<Level> dimension, BlockPos pos) {
        return new BlockKey(dimension, pos);
    }

    /**
     * 稳定 id：同一位置永远同一个 UUID。
     *
     * <p>派生规则是「维度注册名 + ':' + 坐标 long」，经 MD5 转成 UUID。机器和容器共用这一套，
     * 角色不参与——同一个方块当机器还是当容器是同一个 id。这段算法从旧的 {@code LinkIds}
     * 原样搬来，改之前先确认没有别处依赖不同的算法。</p>
     *
     * @return 稳定 id
     */
    public UUID id() {
        String key = this.dimension.location() + ":" + this.pos.asLong();
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }
}
