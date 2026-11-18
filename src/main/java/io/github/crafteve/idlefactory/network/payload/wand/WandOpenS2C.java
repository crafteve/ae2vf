package io.github.crafteve.idlefactory.network.payload.wand;

import java.util.ArrayList;
import java.util.List;

import io.github.crafteve.idlefactory.command.WandDraftCodec;
import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.client.wand.WandCoordinator;
import io.github.crafteve.idlefactory.factory.wand.WandFailure;
import io.github.crafteve.idlefactory.factory.wand.WandIssue;
import io.github.crafteve.idlefactory.factory.wand.WandLimits;

import io.netty.handler.codec.DecoderException;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：打开草稿面板前的预校验结果。
 *
 * <p>{@code issues} 只带前 {@link WandLimits#MAX_ISSUES_WIRE} 条，{@code issueCount} 是总数；
 * {@code failure} 只表示「面板不该打开」的草稿级原因（未持杖 / 无工厂 / 草稿为空等）。逐块问题
 * 不算 failure，由面板标红。</p>
 *
 * @param issues     预校验问题，线上截断
 * @param issueCount 问题总数
 * @param failure    不打开面板的原因；可以打开时为 {@link WandFailure#NONE}
 */
public record WandOpenS2C(List<WandIssue> issues, int issueCount, WandFailure failure)
        implements CustomPacketPayload {
    /** 包类型：{@code idlefactory:wand_open_result}。 */
    public static final Type<WandOpenS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID,
                    "wand_open_result"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, WandOpenS2C> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        int count = Math.min(payload.issues().size(), WandLimits.MAX_ISSUES_WIRE);
                        buf.writeVarInt(count);
                        for (int i = 0; i < count; i++) {
                            WandDraftCodec.ISSUE.encode(buf, payload.issues().get(i));
                        }
                        buf.writeVarInt(payload.issueCount());
                        WandDraftCodec.writeFailure(buf, payload.failure());
                    },
                    buf -> {
                        int count = buf.readVarInt();
                        if (count < 0 || count > WandLimits.MAX_ISSUES_WIRE) {
                            throw new DecoderException("预校验问题数量越界：" + count);
                        }
                        List<WandIssue> issues = new ArrayList<>(count);
                        for (int i = 0; i < count; i++) {
                            issues.add(WandDraftCodec.ISSUE.decode(buf));
                        }
                        return new WandOpenS2C(issues, buf.readVarInt(),
                                WandDraftCodec.readFailure(buf));
                    });

    public WandOpenS2C {
        issues = List.copyOf(issues);
        if (issues.size() > WandLimits.MAX_ISSUES_WIRE || issueCount < issues.size()) {
            throw new IllegalArgumentException("预校验问题条数不合法");
        }
    }

    @Override
    public Type<WandOpenS2C> type() {
        return TYPE;
    }

    /**
     * 客户端处理：缓存问题，随后原版打开菜单事件会构造面板。
     *
     * @param payload 回执
     * @param context 上下文
     */
    public static void handle(WandOpenS2C payload, IPayloadContext context) {
        context.enqueueWork(() -> WandCoordinator.handleOpen(payload));
    }
}
