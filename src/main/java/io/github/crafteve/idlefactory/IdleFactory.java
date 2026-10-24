package io.github.crafteve.idlefactory;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.crafteve.idlefactory.registry.ModCreativeTabs;
import io.github.crafteve.idlefactory.registry.ModItems;
import io.github.crafteve.idlefactory.registry.ModMenus;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

@Mod(IdleFactory.MODID)
public class IdleFactory {
    public static final String MODID = "idlefactory";
    public static final Logger LOGGER = LogUtils.getLogger();

    public IdleFactory(IEventBus modEventBus, ModContainer modContainer) {
        // 所有注册表都挂在 mod 事件总线上
        ModItems.ITEMS.register(modEventBus);
        ModMenus.MENUS.register(modEventBus);
        ModCreativeTabs.TABS.register(modEventBus);

        modEventBus.addListener(this::commonSetup);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Idle Factory common setup");
    }
}
