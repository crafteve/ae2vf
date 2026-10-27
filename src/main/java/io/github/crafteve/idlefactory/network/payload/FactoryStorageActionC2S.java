package io.github.crafteve.idlefactory.network.payload;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.resource.ResourceKey;
import io.github.crafteve.idlefactory.factory.storage.FactoryStorageActions;
import io.github.crafteve.idlefactory.menu.FactoryMenu;
import io.github.crafteve.idlefactory.network.ResourceKeyCodec;

import io.netty.handler.codec.DecoderException;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端对一个仓库格子做出的动作。
 *
 * <p>只传资源和手势，不传数量、不传物品栈；所有实际数量由
 * {@link FactoryStorageActions} 在服务端重新校验。{@code key == null} 表示点在空气格上，
 * 只允许把光标物品放进仓库。</p>
 *
 * @param containerId 菜单容器 id
 * @param key         资源；空气格为 {@code null}
 * @param gesture     手势
 */
public record FactoryStorageActionC2S(int containerId, @Nullable ResourceKey key,
        Gesture gesture) implements CustomPacketPayload {

    /** 包类型：{@code idlefactory:factory_storage_action}。 */
    public static final Type<FactoryStorageActionC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID,
                    "factory_storage_action"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, FactoryStorageActionC2S> STREAM_CODEC =
            StreamCodec.of(FactoryStorageActionC2S::write, FactoryStorageActionC2S::read);

    @Override
    public Type<FactoryStorageActionC2S> type() {
        return TYPE;
    }

    /**
     * 服务端处理：先校验菜单，再交给仓库动作层。
     *
     * @param payload 动作
     * @param context 上下文
     */
    public static void handle(FactoryStorageActionC2S payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!(player.containerMenu instanceof FactoryMenu menu)
                    || menu.containerId != payload.containerId()) {
                return;
            }
            FactoryStorageActions.handle(player, menu, payload.key(), payload.gesture());
        });
    }

    private static void write(RegistryFriendlyByteBuf buf, FactoryStorageActionC2S payload) {
        buf.writeVarInt(payload.containerId());
        buf.writeBoolean(payload.key() != null);
        if (payload.key() != null) {
            ResourceKeyCodec.write(buf, payload.key());
        }
        buf.writeByte(payload.gesture().ordinal());
    }

    private static FactoryStorageActionC2S read(RegistryFriendlyByteBuf buf) {
        int containerId = buf.readVarInt();
        ResourceKey key = null;
        if (buf.readBoolean()) {
            key = ResourceKeyCodec.read(buf);
        }
        int gestureOrdinal = buf.readUnsignedByte();
        if (gestureOrdinal >= Gesture.values().length) {
            throw new DecoderException("invalid storage gesture: " + gestureOrdinal);
        }
        return new FactoryStorageActionC2S(containerId, key, Gesture.values()[gestureOrdinal]);
    }

    /** 仓库格手势。 */
    public enum Gesture {
        /** 空光标取一叠；有光标把光标物品全部放入。 */
        LEFT,
        /** 空光标取半叠；有光标放入一个。 */
        RIGHT,
        /** 仓库 → 背包一叠。 */
        SHIFT_LEFT,
        /** 仓库 → 背包一个。 */
        SHIFT_RIGHT,
        /** Shift+滚轮：仓库 → 背包一个。 */
        SCROLL_UP,
        /** Shift+滚轮：背包 → 仓库一个。 */
        SCROLL_DOWN,
        /** 背包 → 仓库；JEI ghost 等入口使用。 */
        PUT_FROM_INVENTORY
    }
}
