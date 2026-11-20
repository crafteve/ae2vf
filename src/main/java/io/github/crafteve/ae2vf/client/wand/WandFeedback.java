package io.github.crafteve.ae2vf.client.wand;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 绑定杖的反馈出口：草稿状态机与提交流程只在这里说话，不知道 HUD 怎么画。
 *
 * <p>HUD 在 {@code client.screen.wand}，属于表现层；由输入层（{@link MachineWandInput}）注入实现，
 * 状态机与提交器因此不再反向依赖界面类。</p>
 */
public interface WandFeedback {
    /**
     * 成功闪条。
     *
     * @param text 文案
     */
    void success(Component text);

    /**
     * 中性提示。
     *
     * @param text 文案
     */
    void neutral(Component text);

    /**
     * 失败闪条。
     *
     * @param text 文案
     */
    void failure(Component text);

    /** 草稿变更后的一次点击反馈。 */
    void click();

    /**
     * 每 tick 推进闪条生命周期。
     *
     * @param minecraft 客户端实例
     */
    void tick(Minecraft minecraft);

    /** 清空常驻条与闪条。 */
    void clear();

    /**
     * 草稿面板是否开着。
     *
     * <p>状态机用它决定"开别的界面丢草稿"的例外：草稿面板本身是同一场编辑的一部分。</p>
     *
     * @return 面板开着为 {@code true}
     */
    boolean draftScreenOpen();
}
