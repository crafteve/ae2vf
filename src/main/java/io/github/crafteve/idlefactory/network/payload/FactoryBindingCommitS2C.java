package io.github.crafteve.idlefactory.network.payload;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.client.network.ClientBindingCommitHandler;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端的绑定提交结果。
 *
 * <p>客户端只信这个结果：成功才清草稿，失败保留草稿并提示，不做本地乐观应用。</p>
 *
 * @param containerId 菜单容器 id
 * @param ok          是否成功
 * @param nodeId      落到哪个节点；失败时为 {@code null}
 * @param machines    本次新增机器数
 * @param blocks      本次新增方块数
 * @param message     给玩家看的一句话
 */
public record FactoryBindingCommitS2C(int containerId, boolean ok, @Nullable UUID nodeId,
        int machines, int blocks, String message) implements CustomPacketPayload {

    /** 包类型：{@code idlefactory:binding_commit_result}。 */
    public static final Type<FactoryBindingCommitS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID,
                    "binding_commit_result"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, FactoryBindingCommitS2C> STREAM_CODEC =
            StreamCodec.of(FactoryBindingCommitS2C::write, FactoryBindingCommitS2C::read);

    @Override
    public Type<FactoryBindingCommitS2C> type() {
        return TYPE;
    }

    /**
     * 客户端处理。
     *
     * @param payload 结果
     * @param context 上下文
     */
    public static void handle(FactoryBindingCommitS2C payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientBindingCommitHandler.handle(payload));
    }

    private static void write(RegistryFriendlyByteBuf buf, FactoryBindingCommitS2C payload) {
        buf.writeVarInt(payload.containerId());
        buf.writeBoolean(payload.ok());
        buf.writeBoolean(payload.nodeId() != null);
        if (payload.nodeId() != null) {
            buf.writeUUID(payload.nodeId());
        }
        buf.writeVarInt(payload.machines());
        buf.writeVarInt(payload.blocks());
        buf.writeUtf(payload.message());
    }

    private static FactoryBindingCommitS2C read(RegistryFriendlyByteBuf buf) {
        int containerId = buf.readVarInt();
        boolean ok = buf.readBoolean();
        UUID nodeId = buf.readBoolean() ? buf.readUUID() : null;
        int machines = buf.readVarInt();
        int blocks = buf.readVarInt();
        String message = buf.readUtf();
        return new FactoryBindingCommitS2C(containerId, ok, nodeId, machines, blocks, message);
    }
}
