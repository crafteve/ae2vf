package io.github.crafteve.idlefactory.registry;

import io.github.crafteve.idlefactory.IdleFactory;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 创造模式标签页注册表。
 *
 * <p>M0 只放终端一个物品，方便在创造模式里直接拿到。</p>
 */
public final class ModCreativeTabs {
    /** 创造模式标签页注册表。 */
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, IdleFactory.MODID);

    /** Idle Factory 物品页：图标与内容都取终端。 */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> IDLE_FACTORY =
            TABS.register("idlefactory", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.idlefactory"))
                    .icon(() -> new ItemStack(ModItems.TERMINAL.get()))
                    .displayItems((parameters, output) -> output.accept(ModItems.TERMINAL.get()))
                    .build());

    private ModCreativeTabs() {
        // 纯注册表持有类，不实例化
    }
}
