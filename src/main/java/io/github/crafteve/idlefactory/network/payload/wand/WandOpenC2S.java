package io.github.crafteve.idlefactory.network.payload.wand;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.wand.WandDraft;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：编辑态蹲下右键，请求预校验并打开草稿面板。
 *
 * <p>只读预校验与打开菜单都在服务端做；回执先走 {@link WandOpenS2C}，再打开菜单。草稿里
 * 有逐块问题时菜单照样打开（行标红、确定灰显），草稿级问题则不打开。</p>
 *
 * @param draft 当前草稿
 */
public record WandOpenC2S(WandDraft draft) implements CustomPacketPayload {
    /** 包类型：{@code idlefactory:wand_open}。 */
    public static final Type<WandOpenC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID, "wand_open"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, WandOpenC2S> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> WandDraftCodec.DRAFT.encode(buf, payload.draft()),
                    buf -> new WandOpenC2S(WandDraftCodec.DRAFT.decode(buf)));

    @Override
    public Type<WandOpenC2S> type() {
        return TYPE;
    }

    /**
     * 服务端处理：主线程预校验，回执后再打开菜单。
     *
     * @param payload 请求
     * @param context 上下文
     */
    public static void handle(WandOpenC2S payload, IPayloadContext context) {
    }
}
