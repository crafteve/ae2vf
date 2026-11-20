package io.github.crafteve.ae2vf.command;

/**
 * 命令发起者：玩家操作，还是服务端自己。
 *
 * <p>来源决定门禁：SYSTEM 命令不查会话、不回执；PLAYER 命令必须带菜单会话与请求序号。</p>
 */
public enum CommandSource {
    /** 玩家在终端或世界里发起的写操作。 */
    PLAYER,
    /** 服务端自己发起（建厂、类型迁移等）。 */
    SYSTEM
}
