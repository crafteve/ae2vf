package io.github.crafteve.idlefactory.factory.storage;

import java.util.Map;

import appeng.api.stacks.AEKey;
import io.github.crafteve.idlefactory.factory.capability.CapabilityKind;

/**
 * 空仓库：什么都没装，也什么都收不下。
 *
 * <p>它是工厂运行时在「没绑地址」或「仓库暂时连不上」时的降级实现。
 * 工厂拿到它不会以为出了错——它只会看到「没料」和「没地方放」，然后按正常业务逻辑罢工。</p>
 */
public final class EmptyStorage implements Storage {
    /** 无状态，单例即可。 */
    public static final EmptyStorage INSTANCE = new EmptyStorage();

    private EmptyStorage() {
        // 单例
    }

    @Override
    public long insert(AEKey key, long amount, boolean simulate) {
        return 0L;
    }

    @Override
    public long extract(AEKey key, long amount, boolean simulate) {
        return 0L;
    }

    @Override
    public boolean supports(CapabilityKind kind) {
        return false;
    }

    @Override
    public boolean isEmpty() {
        return true;
    }

    @Override
    public Map<AEKey, Long> snapshot() {
        return Map.of();
    }
}
