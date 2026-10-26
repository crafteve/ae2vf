package io.github.crafteve.idlefactory.factory.storage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.observation.ObservationService;

import net.minecraft.server.MinecraftServer;

/**
 * 容器仓库解析器：地址 → 一组可用的容器 → {@link ContainerStorage}。
 *
 * <p>部分容器不可用（被拆、区块未加载）不算失败：只要还剩一个，仓库就在线。全部不可用才返回
 * {@code null}，由插头降级成离线。</p>
 *
 * <p>观测服务是可选的：给了就把它传进 {@link ContainerStorage}，让每次按面搬运走能力缓存、
 * 并按观测到的「哪个面有流体/能量」窄化候选面；没给就退回直接查世界。</p>
 */
public final class ContainerStorageResolver implements StorageResolver {
    private final MinecraftServer server;
    @Nullable
    private final ObservationService observations;

    /**
     * @param server 服务端
     */
    public ContainerStorageResolver(MinecraftServer server) {
        this(server, null);
    }

    /**
     * @param server       服务端
     * @param observations 观测服务；可为 {@code null}
     */
    public ContainerStorageResolver(MinecraftServer server, @Nullable ObservationService observations) {
        this.server = server;
        this.observations = observations;
    }

    @Override
    @Nullable
    public Storage resolve(StorageAnchor anchor) {
        if (!(anchor instanceof ContainerAnchor containerAnchor) || containerAnchor.isEmpty()) {
            return null;
        }
        List<ContainerRef> available = new ArrayList<>(containerAnchor.containers().size());
        for (ContainerRef ref : containerAnchor.containers()) {
            if (ContainerStorage.available(this.server, ref)) {
                available.add(ref);
            }
        }
        if (available.isEmpty()) {
            return null;
        }
        // 高优先级在前：先填、后抽
        available.sort(Comparator.comparingInt(ContainerRef::priority).reversed());
        return new ContainerStorage(this.server, available, containerAnchor.containers().size(),
                this.observations);
    }
}
