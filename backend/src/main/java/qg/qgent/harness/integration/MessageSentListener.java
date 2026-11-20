package qg.qgent.harness.integration;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import qg.qgent.chat.dto.Mention;
import qg.qgent.chat.event.MessageSentEvent;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.chat.service.MessageService;
import qg.qgent.harness.service.AgentRunService;

/**
 * 将新消息中的 Agent 提及登记到与消息相同的事务。
 */
@Component
@RequiredArgsConstructor
public class MessageSentListener {
    private final MessageMapper messages;
    private final MessageService messageService;
    private final AgentRunService runs;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onMessageSent(MessageSentEvent event) {
        if (event.mentions() == null || event.mentions().isEmpty()) return;
        var agentIds = event.mentions().stream()
            .filter(mention -> "AGENT".equals(mention.getType()))
            .map(Mention::getId)
            .distinct()
            .toList();
        if (agentIds.isEmpty()) return;
        // 用户发消息时已校验群成员身份，且只有首次保存消息才发布此事件。
        var message = messages.selectById(event.messageId());
        if (message == null) throw new IllegalStateException("Agent 任务缺少触发消息");
        String instruction = messageService.agentInstruction(message);
        agentIds.forEach(agentId -> runs.enqueueRoot(agentId, event, instruction));
    }
}
