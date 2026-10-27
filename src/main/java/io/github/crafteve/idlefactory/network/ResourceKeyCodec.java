package io.github.crafteve.idlefactory.network;

import io.github.crafteve.idlefactory.factory.resource.ResourceKey;
import io.github.crafteve.idlefactory.factory.resource.ResourceKind;

import io.netty.handler.codec.DecoderException;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * {@link ResourceKey} 的唯一网络编解码。
 *
 * <p>格式：类型 + 注册名；物品再跟一个「是否有组件」，有时写整栈
 * （{@link ItemStack#STREAM_CODEC}）。保留注册名是为了让旧存档里「物品 id 已不存在」的规则
 * 也能安全地发给客户端（客户端显示 id、诊断报无法解析），而不是把包编码炸掉。</p>
 *
 * <p>读侧一律不相信对端：类型越界、栈为空、数量不为 1、栈里的物品与注册名不一致都直接抛
 * {@link DecoderException}。</p>
 */
public final class ResourceKeyCodec {
    private ResourceKeyCodec() {
        // 纯静态工具类
    }

    /**
     * 写一个 key。
     *
     * @param buf 目标缓冲
     * @param key 资源
     */
    public static void write(RegistryFriendlyByteBuf buf, ResourceKey key) {
        buf.writeByte(key.kind().ordinal());
        ResourceLocation.STREAM_CODEC.encode(buf, key.id());
        if (key.kind() != ResourceKind.ITEM) {
            return;
        }
        ItemStack stack = key.toStack();
        buf.writeBoolean(!stack.isEmpty());
        if (!stack.isEmpty()) {
            ItemStack.STREAM_CODEC.encode(buf, stack);
        }
    }

    /**
     * 读一个 key。
     *
     * @param buf 来源缓冲
     * @return 资源
     */
    public static ResourceKey read(RegistryFriendlyByteBuf buf) {
        int ordinal = buf.readUnsignedByte();
        if (ordinal >= ResourceKind.values().length) {
            throw new DecoderException("invalid resource kind: " + ordinal);
        }
        ResourceKind kind = ResourceKind.values()[ordinal];
        ResourceLocation id = ResourceLocation.STREAM_CODEC.decode(buf);
        if (kind != ResourceKind.ITEM) {
            return ResourceKey.of(kind, id);
        }
        if (!buf.readBoolean()) {
            // 未知注册名：保留 id，客户端可以显示、诊断可以报错。
            return ResourceKey.of(kind, id);
        }
        ItemStack stack = ItemStack.STREAM_CODEC.decode(buf);
        if (stack.isEmpty()) {
            throw new DecoderException("empty item key");
        }
        if (stack.getCount() != 1) {
            throw new DecoderException("item key count must be 1: " + stack.getCount());
        }
        if (!BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(id)) {
            throw new DecoderException("item key id mismatch: " + id);
        }
        return ResourceKey.item(stack);
    }
}
