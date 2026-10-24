package io.github.crafteve.idlefactory.network.payload;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.player.ModAttachments;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：请求卸下奇点。
 *
 * <p>为什么需要这个包：原版/NeoForge 的 {@code PlayerInteractEvent.RightClickEmpty} 只在客户端触发，
 * 服务端不知道「空手右键空气」。所以客户端在潜行 + 空手右键空气时发一个空包，服务端再按自己的
 * 附件数据决定是否真的卸下（客户端不能直接改数据）。</p>
 */
public record UnequipSingularityC2S() implements CustomPacketPayload {
    /** 包类型 id：{@code idlefactory:unequip_singularity}。 */
    public static final Type<UnequipSingularityC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID, "unequip_singularity"));

    /** 空负载。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, UnequipSingularityC2S> STREAM_CODEC =
            StreamCodec.unit(new UnequipSingularityC2S());

    @Override
    public Type<UnequipSingularityC2S> type() {
        return TYPE;
    }

    /** 服务端处理：以服务端附件数据为准，卸下并尽量还给玩家。 */
    public static void handle(UnequipSingularityC2S payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }

        ItemStack stored = ModAttachments.get(player);
        if (stored.isEmpty()) {
            return;
        }

        ItemStack toReturn = stored.copy();
        ModAttachments.clear(player);

        if (!player.getInventory().add(toReturn) && !toReturn.isEmpty()) {
            // 背包放不下：掉在脚下，别把奇点吞掉
            player.drop(toReturn, false);
            player.displayClientMessage(Component.translatable("message.idlefactory.singularity_dropped"), false);
        } else {
            player.displayClientMessage(Component.translatable("message.idlefactory.singularity_unequipped"), true);
        }
    }
}
