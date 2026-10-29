package io.github.crafteve.idlefactory.network.payload;

import java.util.ArrayList;
import java.util.List;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.client.network.ClientFactoryLinksHandler;
import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.machine.MachineRef;
import io.github.crafteve.idlefactory.factory.storage.ContainerAnchor;
import io.github.crafteve.idlefactory.factory.storage.ContainerRef;
import io.github.crafteve.idlefactory.factory.storage.MeAnchor;
import io.github.crafteve.idlefactory.factory.storage.StorageAnchor;
import io.github.crafteve.idlefactory.factory.storage.StorageMode;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：当前仓库模式和所有已绑定坐标。
 *
 * <p>客户端只用它画绿/蓝框，不拿它做业务判断。机器只发未退役（回收站里的不发）；仓库地址
 * 按类型展开成坐标列表：ME 一个、容器最多
 * {@link ContainerAnchor#MAX_CONTAINERS} 个。</p>
 *
 * @param mode     当前仓库模式
 * @param storage  仓库坐标（ME / 容器）
 * @param machines 未退役的已连接机器
 */
public record FactoryLinksS2C(StorageMode mode, List<Link> storage, List<Link> machines)
        implements CustomPacketPayload {
    /** 机器列表上限，与机器表快照一致。 */
    public static final int MAX_MACHINES = 512;
    /** 仓库坐标上限，与容器上限一致。 */
    public static final int MAX_STORAGE = 16;

    /** 包类型：{@code idlefactory:factory_links}。 */
    public static final Type<FactoryLinksS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID,
                    "factory_links"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, FactoryLinksS2C> STREAM_CODEC =
            StreamCodec.of(FactoryLinksS2C::write, FactoryLinksS2C::read);

    public FactoryLinksS2C {
        storage = List.copyOf(storage);
        machines = List.copyOf(machines);
    }

    @Override
    public Type<FactoryLinksS2C> type() {
        return TYPE;
    }

    /**
     * 由工厂当前状态生成同步包。
     *
     * @param factory 工厂
     * @return 同步包
     */
    public static FactoryLinksS2C of(Factory factory) {
        List<Link> storage = new ArrayList<>();
        StorageAnchor anchor = factory.config().storageAnchor();
        if (anchor instanceof MeAnchor me) {
            storage.add(new Link(me.dimension(), me.pos()));
        } else if (anchor instanceof ContainerAnchor containers) {
            for (ContainerRef ref : containers.containers()) {
                storage.add(new Link(ref.dimension(), ref.pos()));
            }
        }
        List<Link> machines = new ArrayList<>();
        for (MachineRef ref : factory.config().machines().all()) {
            if (!factory.config().machines().isRetired(ref.id())) {
                machines.add(new Link(ref.dimension(), ref.pos()));
            }
        }
        return new FactoryLinksS2C(factory.config().storageMode(), storage, machines);
    }

    /** 空状态：没有工厂或还没绑定。 */
    public static FactoryLinksS2C empty() {
        return new FactoryLinksS2C(StorageMode.ME, List.of(), List.of());
    }

    /**
     * 客户端处理：缓存同步数据。
     *
     * @param payload 同步包
     * @param context 上下文
     */
    public static void handle(FactoryLinksS2C payload, IPayloadContext context) {
        ClientFactoryLinksHandler.handle(payload, context);
    }

    private static void write(RegistryFriendlyByteBuf buf, FactoryLinksS2C payload) {
        buf.writeEnum(payload.mode());
        writeLinks(buf, payload.storage(), MAX_STORAGE);
        writeLinks(buf, payload.machines(), MAX_MACHINES);
    }

    private static FactoryLinksS2C read(RegistryFriendlyByteBuf buf) {
        StorageMode mode = buf.readEnum(StorageMode.class);
        List<Link> storage = readLinks(buf, MAX_STORAGE);
        List<Link> machines = readLinks(buf, MAX_MACHINES);
        return new FactoryLinksS2C(mode, storage, machines);
    }

    private static void writeLinks(RegistryFriendlyByteBuf buf, List<Link> links, int max) {
        int count = Math.min(links.size(), max);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            Link link = links.get(i);
            buf.writeResourceLocation(link.dimension().location());
            buf.writeBlockPos(link.pos());
        }
    }

    private static List<Link> readLinks(RegistryFriendlyByteBuf buf, int max) {
        int count = buf.readVarInt();
        if (count < 0 || count > max) {
            throw new IllegalArgumentException("Invalid link count: " + count);
        }
        List<Link> links = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
                    buf.readResourceLocation());
            links.add(new Link(dimension, buf.readBlockPos()));
        }
        return links;
    }

    /**
     * 一个坐标。
     *
     * @param dimension 维度
     * @param pos       方块坐标
     */
    public record Link(ResourceKey<Level> dimension, BlockPos pos) {
    }
}
