package io.github.crafteve.ae2vf;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

/**
 * 模组入口：只负责声明 MODID、版本与代理，并把 FML 生命周期转发给代理。
 *
 * <p>
 * 工厂方块、方块实体与 AE2 接入都在后续的领域类里实现，入口类不承载业务逻辑。
 */
@Mod(
    modid = VoidFactory.MODID,
    version = Tags.VERSION,
    name = "AE2 Void Factory",
    acceptedMinecraftVersions = "[1.7.10]",
    dependencies = "required-after:appliedenergistics2")
public class VoidFactory {

    public static final String MODID = "ae2vf";

    public static final Logger LOG = LogManager.getLogger(MODID);

    @SidedProxy(
        clientSide = "io.github.crafteve.ae2vf.ClientProxy",
        serverSide = "io.github.crafteve.ae2vf.CommonProxy")
    public static CommonProxy proxy;

    /**
     * 预初始化：读取配置、注册方块与物品。
     */
    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    /**
     * 初始化：注册事件监听与配方。
     */
    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    /**
     * 后初始化：按其它模组的加载结果补完自身设置。
     */
    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }

    /**
     * 服务端启动：注册指令等只存在于服务端的内容。
     */
    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        proxy.serverStarting(event);
    }
}
