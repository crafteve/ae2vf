package io.github.crafteve.ae2vf;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

/**
 * 模组配置：所有配置读写都从这里走，保证只有一个写入口。
 *
 * <p>
 * 目前没有配置项，只负责创建并保存配置文件；新增配置项时在同一处集中声明。
 */
public final class Config {

    /**
     * 读取配置文件；首次运行会创建默认文件。
     *
     * @param configFile Forge 建议的配置文件路径
     */
    public static void synchronizeConfiguration(File configFile) {
        Configuration configuration = new Configuration(configFile);
        configuration.load();

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }
}
