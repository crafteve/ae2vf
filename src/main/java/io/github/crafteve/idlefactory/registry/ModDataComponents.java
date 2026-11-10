package io.github.crafteve.idlefactory.registry;

import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.BindingDraft;
import io.github.crafteve.idlefactory.factory.BindingMode;
import io.github.crafteve.idlefactory.factory.location.BlockKey;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 数据组件注册表。
 *
 * <p>绑定杖草稿组件带 Codec，因此会随物品栈正常落盘：这是预期行为，不是需要规避的东西。
 * 草稿的语义仍是「不跨登录保留」——登录 / 换维度 / 换手 / 把杖放进容器时客户端会清空草稿
 * 并从组件里移除，所以落盘的那一份不会被继续使用。</p>
 *
 * <p>不提供网络 StreamCodec：草稿是客户端 UI 状态，服务端不读它，也不靠它做绑定校验；
 * 权威仍是提交包。</p>
 */
public final class ModDataComponents {
    /** 数据组件注册表。 */
    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, IdleFactory.MODID);

    /** 一个方块位置：维度注册名 + 坐标 long。 */
    private static final Codec<BlockKey> BLOCK_KEY_CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    ResourceLocation.CODEC.fieldOf("dimension")
                            .forGetter(key -> key.dimension().location()),
                    Codec.LONG.fieldOf("pos").forGetter(key -> key.pos().asLong())
            ).apply(instance, (dimension, pos) -> BlockKey.of(
                    ResourceKey.create(Registries.DIMENSION, dimension), BlockPos.of(pos))));

    /** 一台机器：一个方块。 */
    private static final Codec<BindingDraft.MachineDraft> MACHINE_CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    BLOCK_KEY_CODEC.fieldOf("block")
                            .forGetter(BindingDraft.MachineDraft::block)
            ).apply(instance, BindingDraft.MachineDraft::new));

    /** 草稿：模式 + 可选节点 id + 机器列表。 */
    public static final Codec<BindingDraft> DRAFT_CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.xmap(BindingMode::read, BindingMode::name)
                            .fieldOf("mode").forGetter(BindingDraft::mode),
                    Codec.STRING.optionalFieldOf("node")
                            .forGetter(draft -> Optional.ofNullable(draft.nodeId())
                                    .map(UUID::toString)),
                    MACHINE_CODEC.listOf().fieldOf("machines")
                            .forGetter(BindingDraft::machines)
            ).apply(instance, (mode, node, machines) -> new BindingDraft(mode,
                    node.map(UUID::fromString).orElse(null), machines)));

    /** 绑定杖草稿组件；persistent 是预期行为。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BindingDraft>>
            BINDING_DRAFT = COMPONENTS.register("binding_draft",
                    () -> DataComponentType.<BindingDraft>builder()
                            .persistent(DRAFT_CODEC)
                            .build());

    private ModDataComponents() {
        // 纯注册表持有类，不实例化
    }
}
