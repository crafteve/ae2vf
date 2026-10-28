package io.github.crafteve.idlefactory.registry;

import io.github.crafteve.idlefactory.IdleFactory;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 创造模式物品栏。
 *
 * <p>开发期只放本模组的三个物品，方便直接取用；后续有方块或更多物品再扩展。</p>
 */
public final class ModCreativeTabs {
    /** 创造栏注册表。 */
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, IdleFactory.MODID);

    /** 放置工厂物品栏：图标用工厂终端。 */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> IDLE_FACTORY =
            TABS.register("idlefactory", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.idlefactory"))
                    .icon(() -> new ItemStack(ModItems.FACTORY_TERMINAL.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(ModItems.FACTORY_TERMINAL.get());
                        output.accept(ModItems.MACHINE_BINDING_WAND.get());
                        output.accept(ModItems.STORAGE_BINDING_WAND.get());
                    })
                    .build());

    private ModCreativeTabs() {
        // 纯注册表持有类，不实例化
    }
}
