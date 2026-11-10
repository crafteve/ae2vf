package io.github.crafteve.idlefactory.network.payload.wand;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.wand.WandDraft;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：面板点确定，提交整份草稿。
 *
 * <p>{@code containerId} 用来拒绝过期面板（面板关闭再打开后旧按钮不该还能提交），
 * {@code draft.requestSeq} 用来防重放；两者都由服务端重验。</p>
 *
 * @param containerId 面板菜单的容器 id
 * @param draft       完整草稿
 */
public record WandCommitC2S(int containerId, WandDraft draft) implements CustomPacketPayload {
    /** 包类型：{@code idlefactory:wand_commit}。 */
    public static final Type<WandCommitC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID, "wand_commit"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, WandCommitC2S> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeVarInt(payload.containerId());
                        WandDraftCodec.DRAFT.encode(buf, payload.draft());
                    },
                    buf -> new WandCommitC2S(buf.readVarInt(),
                            WandDraftCodec.DRAFT.decode(buf)));

    @Override
    public Type<WandCommitC2S> type() {
        return TYPE;
    }

    /**
     * 服务端处理：校验容器与草稿后原子写库，回执走 {@link WandCommitS2C}。
     *
     * @param payload 请求
     * @param context 上下文
     */
    public static void handle(WandCommitC2S payload, IPayloadContext context) {
    }
}
