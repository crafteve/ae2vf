package io.github.crafteve.ae2vf.node;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.ae2vf.machine.Machine;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/**
 * 节点的存档编解码：身份、类型、IO、样板聚合与机器表。
 *
 * <p>只有新档键名（{@code Craft}）；旧档 {@code Provider} 在读侧兼容一次。缺 id / 类型 / 机器
 * 时丢弃整份节点；坏机器逐个丢弃，读完一台不剩才丢节点。</p>
 */
public final class NodeCodec {
    private static final String TAG_ID = "Id";
    private static final String TAG_NAME = "Name";
    private static final String TAG_TYPE_BLOCK_ID = "TypeBlockId";
    private static final String TAG_IO = "Io";
    private static final String TAG_CRAFT = "Craft";
    private static final String TAG_LEGACY_PROVIDER = "Provider";
    private static final String TAG_MACHINES = "Machines";

    private NodeCodec() {
        // 纯静态编解码
    }

    /**
     * 序列化一个节点。
     *
     * @param node       节点
     * @param registries 注册表访问
     * @return 新标签
     */
    public static CompoundTag save(Node node, HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(TAG_ID, node.id());
        if (node.name() != null) {
            tag.putString(TAG_NAME, node.name());
        }
        tag.putString(TAG_TYPE_BLOCK_ID, node.typeBlockId().toString());
        tag.put(TAG_IO, node.io().save(registries));
        tag.put(TAG_CRAFT, node.provider().save(registries));
        ListTag list = new ListTag();
        for (Machine machine : node.machines()) {
            list.add(machine.save());
        }
        tag.put(TAG_MACHINES, list);
        return tag;
    }

    /**
     * 读回一个节点。
     *
     * @param tag        存档标签
     * @param registries 注册表访问
     * @return 节点；无法恢复时为 {@code null}
     */
    @Nullable
    public static Node load(CompoundTag tag, HolderLookup.Provider registries) {
        if (!tag.hasUUID(TAG_ID)) {
            return null;
        }
        ResourceLocation typeId = ResourceLocation.tryParse(tag.getString(TAG_TYPE_BLOCK_ID));
        if (typeId == null) {
            return null;
        }
        ListTag machineList = tag.getList(TAG_MACHINES, Tag.TAG_COMPOUND);
        List<Machine> machines = new ArrayList<>(machineList.size());
        for (int i = 0; i < machineList.size(); i++) {
            Machine machine = Machine.load(machineList.getCompound(i));
            if (machine != null) {
                machines.add(machine);
            }
        }
        if (machines.isEmpty()) {
            return null;
        }
        NodeIo io = NodeIo.load(tag.getCompound(TAG_IO), registries, tag.getUUID(TAG_ID));
        // 旧档写的是 Provider；新档写 Craft，读侧两种都认。
        CompoundTag craftTag = tag.contains(TAG_CRAFT) ? tag.getCompound(TAG_CRAFT)
                : tag.getCompound(TAG_LEGACY_PROVIDER);
        NodeCraft provider = NodeCraft.load(craftTag, registries);
        String name = tag.contains(TAG_NAME) ? tag.getString(TAG_NAME) : null;
        return new Node(tag.getUUID(TAG_ID), name, typeId, io, provider, machines, 0L);
    }
}
