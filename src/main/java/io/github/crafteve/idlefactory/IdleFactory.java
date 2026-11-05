package io.github.crafteve.idlefactory;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.crafteve.idlefactory.factory.FactoryLimitsConfig;
import io.github.crafteve.idlefactory.network.ModNetwork;
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
        // 注册表挂在 mod 事件总线上：菜单、物品、创造栏
        ModMenus.MENUS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModCreativeTabs.TABS.register(modEventBus);

        // 网络包注册（P0 只有通道自检包）
        ModNetwork.register(modEventBus);

        modEventBus.addListener(this::commonSetup);
        // 工厂上限与观测调优统一走 COMMON 配置；旧的模板 Config 已删除
        modContainer.registerConfig(ModConfig.Type.COMMON, FactoryLimitsConfig.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Idle Factory common setup");
    }
}
