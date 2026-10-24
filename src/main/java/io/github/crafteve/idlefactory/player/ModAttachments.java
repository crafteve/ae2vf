package io.github.crafteve.idlefactory.player;

import io.github.crafteve.idlefactory.IdleFactory;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * 玩家数据附件注册表。
 *
 * <p>量子纠缠奇点不放在 GUI 的常驻槽位里，而是作为玩家附件保存：随玩家存档、跨维度，
 * 死亡后仍然保留（{@code copyOnDeath}）。</p>
 */
public final class ModAttachments {
    /** 附件类型注册表。 */
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, IdleFactory.MODID);

    /**
     * 已装备的奇点，默认空栈。
     *
     * <p>注意：{@link ItemStack#CODEC} 不接受空栈，所以本类只在非空时写入，卸下走 {@link #clear}。</p>
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<ItemStack>> SINGULARITY =
            ATTACHMENT_TYPES.register("singularity", () -> AttachmentType.builder(() -> ItemStack.EMPTY)
                    .serialize(ItemStack.CODEC)
                    .copyOnDeath()
                    .build());

    private ModAttachments() {
        // 纯注册表持有类，不实例化
    }

    /**
     * @return 玩家当前装备的奇点，未装备时为空栈
     */
    public static ItemStack get(Player player) {
        return player.getData(SINGULARITY);
    }

    /**
     * 装备一个奇点（会存副本，避免和手里的栈共享引用）。传空栈等同 {@link #clear}。
     */
    public static void set(Player player, ItemStack stack) {
        if (stack.isEmpty()) {
            clear(player);
        } else {
            player.setData(SINGULARITY, stack.copy());
        }
    }

    /** 卸下奇点。 */
    public static void clear(Player player) {
        player.removeData(SINGULARITY);
    }

    /** 是否已装备奇点。 */
    public static boolean isEquipped(Player player) {
        return !get(player).isEmpty();
    }
}
