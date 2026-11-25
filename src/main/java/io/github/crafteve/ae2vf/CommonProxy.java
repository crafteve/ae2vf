package io.github.crafteve.ae2vf;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

/**
 * 通用代理：客户端与服务端共享的生命周期钩子。
 *
 * <p>
 * 需要区分两侧的实现放到 {@link ClientProxy} 覆盖，公共逻辑留在这里。
 */
public class CommonProxy {

    /**
     * 预初始化：创建并读取配置文件。
     */
    public void preInit(FMLPreInitializationEvent event) {
        Config.synchronizeConfiguration(event.getSuggestedConfigurationFile());
        VoidFactory.LOG.info("AE2 Void Factory {} loaded", Tags.VERSION);
    }

    /**
     * 初始化：此时注册表已可用，适合注册事件监听与配方。
     */
    public void init(FMLInitializationEvent event) {}

    /**
     * 后初始化：此时其它模组已加载完毕。
     */
    public void postInit(FMLPostInitializationEvent event) {}

    /**
     * 服务端启动：注册服务端指令。
     */
    public void serverStarting(FMLServerStartingEvent event) {}
}
