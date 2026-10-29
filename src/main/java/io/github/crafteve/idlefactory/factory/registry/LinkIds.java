package io.github.crafteve.idlefactory.factory.registry;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 统一 id 派生：维度 + 坐标，角色不参与。
 *
 * <p>同一个方块，当机器还是当容器算出来是同一个 id——这正是门禁的前提。以前容器带
 * {@code "container:"} 前缀，同一个方块能算出两个 id，洞就在这里。</p>
 *
 * <p>注意这是单向派生（哈希），反解不出坐标；需要坐标的地方一律拿着引用本身，
 * id 只用来做 map 的键和跨表比对。</p>
 */
public final class LinkIds {
    private LinkIds() {
        // 纯静态工具类
    }

    /**
     * 按坐标派生统一 id。
     *
     * @param dimension 所在维度
     * @param pos       方块坐标
     * @return 全服唯一的占用 id（同一方块永远同一个）
     */
    public static UUID of(ResourceKey<Level> dimension, BlockPos pos) {
        String key = dimension.location() + ":" + pos.asLong();
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }
}
