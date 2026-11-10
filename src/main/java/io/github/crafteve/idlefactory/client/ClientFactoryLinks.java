package io.github.crafteve.idlefactory.client;

import java.util.List;

import io.github.crafteve.idlefactory.factory.storage.StorageMode;
import io.github.crafteve.idlefactory.network.payload.FactoryLinksS2C;

/**
 * 客户端链接缓存：服务端 {@link FactoryLinksS2C} 的最新快照。
 *
 * <p>只用于画绿/蓝框和显示当前模式，不参与业务判断。登录、换维度、手持绑定杖时由
 * {@code ClientWandInput} 请求刷新。</p>
 */
public final class ClientFactoryLinks {
    private static StorageMode mode = StorageMode.ME;
    private static List<FactoryLinksS2C.Link> storage = List.of();
    private static List<FactoryLinksS2C.Link> machines = List.of();
    private static boolean loaded;

    private ClientFactoryLinks() {
        // 纯静态缓存
    }

    /**
     * 用服务端快照整体替换缓存。
     *
     * @param payload 同步包
     */
    public static void update(FactoryLinksS2C payload) {
        mode = payload.mode();
        storage = payload.storage();
        machines = payload.machines();
        loaded = true;
    }

    /** 清空缓存；登录/登出时调。 */
    public static void clear() {
        mode = StorageMode.ME;
        storage = List.of();
        machines = List.of();
        loaded = false;
    }

    /** 当前模式。 */
    public static StorageMode mode() {
        return mode;
    }

    /** 仓库坐标（ME 一个 / 容器多个）。 */
    public static List<FactoryLinksS2C.Link> storage() {
        return storage;
    }

    /** 未退役的已连接机器。 */
    public static List<FactoryLinksS2C.Link> machines() {
        return machines;
    }

}
