package qg.qgent.chat.event;

import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import qg.qgent.chat.dto.Mention;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.entity.MessageEntity;
import qg.qgent.chat.mapper.ChatGroupMapper;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.chat.service.MessageService;
import qg.qgent.support.TestIds;

class UserMentionListenerTest {
    private final MessageMapper messages = mock(MessageMapper.class);
    private final ChatGroupMapper groups = mock(ChatGroupMapper.class);
    private final MessageService service = mock(MessageService.class);
    private final UserMentionListener listener = new UserMentionListener(messages, groups, service);

    @Test
    void userAndQgentMentionStillNotifiesUsersWithChatContext() {
        var event =
                event(List.of(mention("USER", TestIds.next()), mention("AGENT", TestIds.next())));
        var message = new MessageEntity();
        var group = new ChatGroupEntity();
        group.setName("项目讨论");
        when(messages.selectById(event.messageId())).thenReturn(message);
        when(groups.selectById(event.groupId())).thenReturn(group);
        when(service.extractTextPreview(message)).thenReturn("看一下这个需求");

        listener.onMessageSent(event);

        verify(service).notifyMentionedUsersAfterCommit(event, "项目讨论", "看一下这个需求");
    }

    @Test
    void qgentOnlyAndOrdinaryMessagesDoNotCreateUserNotifications() {
        listener.onMessageSent(event(List.of(mention("AGENT", TestIds.next()))));
        listener.onMessageSent(event(List.of()));
        verifyNoInteractions(messages, groups, service);
    }

    private Mention mention(String type, Long id) {
        var mention = new Mention();
        mention.setType(type);
        mention.setId(id);
        return mention;
    }

    private MessageSentEvent event(List<Mention> mentions) {
        return new MessageSentEvent(
                TestIds.next(), TestIds.next(), TestIds.next(), TestIds.next(), mentions);
    }
}
