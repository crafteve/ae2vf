package io.github.crafteve.ae2vf;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.crafteve.ae2vf.config.VoidFactoryConfig;
import io.github.crafteve.ae2vf.network.NetworkLimit;
import io.github.crafteve.ae2vf.network.NetworkVersion;
import io.github.crafteve.ae2vf.registry.VoidFactoryCreativeTabs;
import io.github.crafteve.ae2vf.registry.VoidFactoryItems;
import io.github.crafteve.ae2vf.registry.VoidFactoryMenus;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

@Mod(VoidFactory.MODID)
public class VoidFactory {
    public static final String MODID = "ae2vf";
    public static final Logger LOGGER = LogUtils.getLogger();

    public VoidFactory(IEventBus modEventBus, ModContainer modContainer) {
        // 注册表挂在 mod 事件总线上：菜单、物品、创造栏
        VoidFactoryMenus.MENUS.register(modEventBus);
        VoidFactoryItems.ITEMS.register(modEventBus);
        VoidFactoryCreativeTabs.TABS.register(modEventBus);

        // 网络包注册（P0 只有通道自检包）
        io.github.crafteve.ae2vf.registry.VoidFactoryPayloads.register(modEventBus);

        modEventBus.addListener(this::commonSetup);
        // 工厂上限与观测调优统一走 COMMON 配置；旧的模板 Config 已删除
        modContainer.registerConfig(ModConfig.Type.COMMON, VoidFactoryConfig.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("AE2 Void Factory common setup");
    }
}
