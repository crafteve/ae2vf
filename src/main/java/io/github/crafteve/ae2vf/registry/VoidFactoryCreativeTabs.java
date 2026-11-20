package io.github.crafteve.ae2vf.registry;

import io.github.crafteve.ae2vf.VoidFactory;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 创造模式物品栏。
 *
 * <p>开发期放本模组的终端、绑定杖，方便直接取用；后续有方块或更多物品再扩展。</p>
 */
public final class VoidFactoryCreativeTabs {
    /** 创造栏注册表。 */
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, VoidFactory.MODID);

    /** 本模组物品栏：图标用被动 IO 终端。 */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CREATIVE_TAB =
            TABS.register("ae2vf", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.ae2vf"))
                    .icon(() -> new ItemStack(VoidFactoryItems.PASSIVE_IO_TERMINAL.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(VoidFactoryItems.PASSIVE_IO_TERMINAL.get());
                        output.accept(VoidFactoryItems.NODE_TERMINAL.get());
                        output.accept(VoidFactoryItems.MACHINE_BINDING_WAND.get());
                        output.accept(VoidFactoryItems.ME_BINDING_WAND.get());
                    })
                    .build());

    private VoidFactoryCreativeTabs() {
        // 纯注册表持有类，不实例化
    }
}
