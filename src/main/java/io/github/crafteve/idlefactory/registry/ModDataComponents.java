package io.github.crafteve.idlefactory.registry;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.BindingDraft;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 数据组件注册表。
 *
 * <p>只注册绑定杖草稿组件，且刻意<b>不</b>设置 {@code persistent} 与
 * {@code networkSynchronized}：草稿不落盘、不跨登录保留，也不随物品同步；权威副本在客户端
 * 静态缓存，服务端只信提交包。</p>
 */
public final class ModDataComponents {
    /** 数据组件注册表。 */
    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, IdleFactory.MODID);

    /**
     * 绑定杖草稿组件。
     *
     * <p>不提供 Codec / StreamCodec，因此既不会写进物品 NBT，也不会走网络同步；只用它把
     * 草稿镜像到物品栈上供 tooltip 与界面恢复。</p>
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BindingDraft>>
            BINDING_DRAFT = COMPONENTS.register("binding_draft",
                    () -> DataComponentType.<BindingDraft>builder().build());

    private ModDataComponents() {
        // 纯注册表持有类，不实例化
    }
}
