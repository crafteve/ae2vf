package io.github.crafteve.idlefactory.factory.machine;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.location.BlockKey;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/**
 * 机器库里的一个真实机器：身份 + 位置 + 这台机器的 IO 规则 + 样板供应器数据。
 *
 * <p>{@link MachineRef} 负责“哪台机器”，{@link MachineIoGroup} 负责“搬什么”，
 * {@link MachineProvider} 负责“主动合成要什么”；本类只是把它们绑在一起。规则与样板都跟着
 * 机器走：只有删除机器才会丢。</p>
 *
 * <p>一台机器当前只有一个 {@link MachineIoGroup} 和一个 {@link MachineProvider}；
 * 以后如果要做样板组，再把这些字段换成可选的规则集。</p>
 */
public final class Machine {
    private static final String TAG_IO = "Io";
    private static final String TAG_PROVIDER = "Provider";

    /** 机器身份与坐标。 */
    private final MachineRef ref;
    /** 这台机器的 IO 规则。 */
    private final MachineIoGroup io;
    /** 这台机器的样板供应器数据。 */
    private final MachineProvider provider;

    /**
     * 新建一台没有规则、没有样板的机器。
     *
     * @param ref 机器身份
     */
    public Machine(MachineRef ref) {
        this(ref, new MachineIoGroup(), new MachineProvider());
    }

    /**
     * @param ref 机器身份
     * @param io  IO 规则
     */
    public Machine(MachineRef ref, MachineIoGroup io) {
        this(ref, io, new MachineProvider());
    }

    /**
     * @param ref      机器身份
     * @param io       IO 规则
     * @param provider 样板供应器数据
     */
    public Machine(MachineRef ref, MachineIoGroup io, MachineProvider provider) {
        this.ref = ref;
        this.io = io;
        this.provider = provider;
    }

    /** 机器 id；等于 {@code ref.id()}。 */
    public UUID id() {
        return this.ref.id();
    }

    /** 机器身份与坐标。 */
    public MachineRef ref() {
        return this.ref;
    }

    /** 这台机器的 IO 规则。 */
    public MachineIoGroup io() {
        return this.io;
    }

    /** 这台机器的样板供应器数据。 */
    public MachineProvider provider() {
        return this.provider;
    }

    /** 规则总数。 */
    public int ruleCount() {
        return this.io.ruleCount();
    }

    /**
     * 序列化成 NBT：机器身份 + IO 规则 + 样板供应器数据。
     *
     * @param registries 注册表访问，物品组件编码需要
     * @return 新标签
     */
    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = this.ref.save();
        tag.put(TAG_IO, this.io.save(registries));
        tag.put(TAG_PROVIDER, this.provider.save(registries));
        return tag;
    }

    /**
     * 从 NBT 读回来；id 与维度+坐标不一致时丢弃。
     *
     * @param tag        存档标签
     * @param registries 注册表访问，物品组件解码需要
     * @return 机器；字段缺失或 id 不一致时返回 {@code null}
     */
    @Nullable
    public static Machine load(CompoundTag tag, HolderLookup.Provider registries) {
        MachineRef ref = MachineRef.load(tag);
        if (ref == null) {
            return null;
        }
        UUID derived = BlockKey.of(ref.dimension(), ref.pos()).id();
        if (!derived.equals(ref.id())) {
            return null;
        }
        MachineIoGroup io = MachineIoGroup.load(tag.getCompound(TAG_IO), registries, ref.id());
        MachineProvider provider = MachineProvider.load(tag.getCompound(TAG_PROVIDER), registries);
        return new Machine(ref, io, provider);
    }
}
