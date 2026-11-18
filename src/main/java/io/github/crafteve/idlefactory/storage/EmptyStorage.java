package io.github.crafteve.idlefactory.storage;

import java.util.Map;

import appeng.api.stacks.AEKey;
import io.github.crafteve.idlefactory.factory.capability.CapabilityKind;
import appeng.api.storage.MEStorage;

/**
 * 空仓库：什么都没装，也什么都收不下。
 *
 * <p>它是工厂运行时在「没绑地址」或「仓库暂时连不上」时的降级实现。
 * 工厂拿到它不会以为出了错——它只会看到「没料」和「没地方放」，然后按正常业务逻辑罢工。</p>
 */
public final class EmptyStorage implements MEStorage {
    /** 无状态，单例即可。 */
    public static final EmptyStorage INSTANCE = new EmptyStorage();

    private EmptyStorage() {
        // 单例
    }

    public long insert(AEKey key, long amount, boolean simulate) {
        return 0L;
    }

    public long extract(AEKey key, long amount, boolean simulate) {
        return 0L;
    }

    public boolean supports(CapabilityKind kind) {
        return false;
    }

    public boolean isEmpty() {
        return true;
    }

    public Map<AEKey, Long> snapshot() {
        return Map.of();
    }

    /** 仓库在 AE2 快照里的描述文本。 */
    @Override
    public net.minecraft.network.chat.Component getDescription() {
        return net.minecraft.network.chat.Component.literal("Idle Factory 空仓库");
    }

    /** AE2 侧的写入入口：转调布尔版本，转换只在这里发生。 */
    @Override
    public long insert(appeng.api.stacks.AEKey what, long amount,
            appeng.api.config.Actionable mode, appeng.api.networking.security.IActionSource source) {
        return insert(what, amount, mode == appeng.api.config.Actionable.SIMULATE);
    }

    /** AE2 侧的取出入口：转调布尔版本。 */
    @Override
    public long extract(appeng.api.stacks.AEKey what, long amount,
            appeng.api.config.Actionable mode, appeng.api.networking.security.IActionSource source) {
        return extract(what, amount, mode == appeng.api.config.Actionable.SIMULATE);
    }

}
