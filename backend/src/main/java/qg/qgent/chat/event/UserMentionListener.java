package qg.qgent.chat.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import qg.qgent.chat.mapper.ChatGroupMapper;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.chat.service.MessageService;

/**
 * 消息提交后通知被提及的用户，与 Qgent 执行相互独立。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserMentionListener {
    private final MessageMapper messages;
    private final ChatGroupMapper groups;
    private final MessageService service;

    @Async("agentExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessageSent(MessageSentEvent event) {
        if (event.mentions() == null
            || event.mentions().stream().noneMatch(m -> "USER".equals(m.getType()))) return;
        try {
            var message = messages.selectById(event.messageId());
            var group = groups.selectById(event.groupId());
            service.notifyMentionedUsersAfterCommit(
                event,
                group == null ? "群聊" : group.getName(),
                service.extractTextPreview(message));
        } catch (RuntimeException exception) {
            log.warn(
                "user mention post-processing skipped, messageId={}: {}",
                event.messageId(),
                exception.getMessage());
        }
    }
}
