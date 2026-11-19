package qg.qgent.chat.event;

/**
 * 新消息已落库（含 Agent 回复和普通聊天），供群记忆等后台状态维护使用。
 */
public record GroupMessageCreatedEvent(Long projectId, Long groupId) {
}
