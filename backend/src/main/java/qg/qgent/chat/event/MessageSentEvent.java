package qg.qgent.chat.event;

import qg.qgent.chat.dto.Mention;

import java.util.List;

/**
 * 新建用户消息的提及事件：提交前登记 Agent 任务，提交后通知被提及用户。
 */
public record MessageSentEvent(
    Long actor, Long projectId, Long groupId, Long messageId, List<Mention> mentions) {
}
