package io.github.crafteve.idlefactory.factory;

import io.github.crafteve.idlefactory.factory.links.LinkRegistry;
import io.github.crafteve.idlefactory.factory.links.LinkRole;

/**
 * 占用门禁的拒绝文案。
 *
 * <p>门禁只回答「能不能」，把一次 {@link LinkGate.ClaimCheck} 翻译成人话是展示层的事。
 * 文案集中在这里，命令与绑定杖三条路径才能保证逐字一致。</p>
 */
final class LinkGateMessages {
    private LinkGateMessages() {
        // 纯静态工具类
    }

    /**
     * 把一次占用检查翻译成拒绝理由；不该拒绝时返回 {@code null}。
     *
     * @param check 检查结果
     * @return 拒绝文案；放行时为 {@code null}
     */
    static String gateMessage(LinkGate.ClaimCheck check) {
        LinkRegistry.Claim claim = check.claim();
        if (claim == null) {
            return null;
        }
        if (check.kind() == LinkGate.ClaimCheck.Kind.SELF_OTHER) {
            return "这个位置已经是你的" + roleName(claim.role()) + "，先解除再操作";
        }
        return "该位置已被" + describeClaim(claim) + "占用";
    }

    /**
     * 把一条认领翻译成人话：谁家的、机器还是仓库。
     *
     * @param claim 认领
     * @return 如“工厂 ab12cd34 的机器”
     */
    private static String describeClaim(LinkRegistry.Claim claim) {
        return "工厂 " + claim.factoryId().toString().substring(0, 8) + " 的" + roleName(claim.role());
    }

    /**
     * 角色名。
     *
     * @param role 角色
     * @return “机器”或“仓库”
     */
    private static String roleName(LinkRole role) {
        return role == LinkRole.MACHINE ? "机器" : "仓库";
    }
}
