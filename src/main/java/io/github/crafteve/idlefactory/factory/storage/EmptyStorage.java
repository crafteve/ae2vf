package io.github.crafteve.idlefactory.factory.storage;

import java.util.Map;

import io.github.crafteve.idlefactory.factory.resource.ResourceKey;
import io.github.crafteve.idlefactory.factory.resource.ResourceKind;

/**
 * 空仓库：什么都没装，也什么都收不下。
 *
 * <p>它是 {@link StorageLink} 在「没绑地址」或「仓库暂时连不上」时的降级实现。
 * 工厂拿到它不会以为出了错——它只会看到「没料」和「没地方放」，然后按正常业务逻辑罢工。</p>
 */
public final class EmptyStorage implements Storage {
    /** 无状态，单例即可。 */
    public static final EmptyStorage INSTANCE = new EmptyStorage();

    private EmptyStorage() {
        // 单例
    }

    @Override
    public long insert(ResourceKey key, long amount, boolean simulate) {
        return 0L;
    }

    @Override
    public long extract(ResourceKey key, long amount, boolean simulate) {
        return 0L;
    }

    @Override
    public boolean supports(ResourceKind kind) {
        return false;
    }

    @Override
    public boolean isEmpty() {
        return true;
    }

    @Override
    public Map<ResourceKey, Long> snapshot() {
        return Map.of();
    }
}
