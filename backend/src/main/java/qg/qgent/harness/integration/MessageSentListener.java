package qg.qgent.harness.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import qg.qgent.chat.dto.Mention;
import qg.qgent.chat.entity.MessageEntity;
import qg.qgent.chat.event.MessageSentEvent;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.chat.service.GroupService;
import qg.qgent.chat.service.MessageService;
import qg.qgent.harness.runtime.AgentChatService;

import java.util.List;

/**
 * 将已提交的 Agent 提及适配为 Harness 运行；普通聊天和用户提及不调用模型。
 */
@Component
public class MessageSentListener {
    private static final Logger log = LoggerFactory.getLogger(MessageSentListener.class);

    private final MessageMapper messageMapper;
    private final AgentChatService agentChatService;
    private final MessageService messageService;
    private final GroupService groupService;

    public MessageSentListener(
        MessageMapper messageMapper,
        AgentChatService agentChatService,
        MessageService messageService,
        GroupService groupService) {
        this.messageMapper = messageMapper;
        this.agentChatService = agentChatService;
        this.messageService = messageService;
        this.groupService = groupService;
    }

    /**
     * 消息提交后异步执行，不占用发送消息的请求线程。
     * <p>
     * 每个被 @ 且在当前群内的 Agent 各自启动一轮运行；重复提及同一 Agent 只启动一次。
     */
    @Async("agentExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessageSent(MessageSentEvent event) {
        Long messageId = event.messageId();
        List<Mention> mentions = event.mentions();
        if (mentions == null || mentions.isEmpty()) {
            return;
        }
        List<Long> agentIds =
            mentions.stream()
                .filter(mention -> "AGENT".equals(mention.getType()))
                .map(Mention::getId)
                .distinct()
                .toList();
        if (agentIds.isEmpty()) {
            return;
        }
        try {
            MessageEntity message = messageMapper.selectById(messageId);
            if (message == null) {
                return;
            }
            String instruction = messageService.agentInstruction(message);
            List<Long> groupAgentIds = groupService.groupAgentIds(event.groupId());
            agentIds.stream()
                .filter(groupAgentIds::contains)
                .forEach(agentId -> runOne(event, agentId, instruction));
        } catch (RuntimeException e) {
            log.warn(
                "message sent post-processing skipped, messageId={}: {}",
                messageId,
                e.getMessage());
        }
    }

    /**
     * 一个 Agent 的运行失败不能连坐其余被提及的 Agent，每个提及各自兜住异常。
     */
    private void runOne(MessageSentEvent event, Long agentId, String instruction) {
        try {
            agentChatService.replyToMention(event, agentId, instruction);
        } catch (RuntimeException e) {
            log.warn(
                "agent run skipped, groupId={}, agentId={}: {}",
                event.groupId(),
                agentId,
                e.getMessage());
        }
    }
}
