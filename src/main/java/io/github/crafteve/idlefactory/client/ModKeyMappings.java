package io.github.crafteve.idlefactory.client;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.KeyMapping;

/**
 * 客户端按键绑定。
 *
 * <p>默认 R 打开工厂终端；玩家可以在「选项 → 控制 → 按键绑定」里改。是否真的能打开由
 * 玩家背包里有没有终端决定，按键本身不判断。</p>
 */
public final class ModKeyMappings {
    /** 打开工厂终端：默认 R。 */
    public static final KeyMapping OPEN_TERMINAL = new KeyMapping(
            "key.idlefactory.open_terminal",
            GLFW.GLFW_KEY_R,
            "key.categories.idlefactory");

    private ModKeyMappings() {
        // 纯常量持有类，不实例化
    }
}
