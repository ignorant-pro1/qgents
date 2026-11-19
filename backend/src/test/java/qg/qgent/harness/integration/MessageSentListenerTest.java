package qg.qgent.harness.integration;

import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import qg.qgent.chat.dto.Mention;
import qg.qgent.chat.entity.MessageEntity;
import qg.qgent.chat.event.MessageSentEvent;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.chat.service.GroupService;
import qg.qgent.chat.service.MessageService;
import qg.qgent.harness.runtime.AgentChatService;
import qg.qgent.support.TestIds;

class MessageSentListenerTest {
    private final MessageMapper messages = mock(MessageMapper.class);
    private final AgentChatService agents = mock(AgentChatService.class);
    private final MessageService messageService = mock(MessageService.class);
    private final GroupService groupService = mock(GroupService.class);
    private final MessageSentListener listener =
            new MessageSentListener(messages, agents, messageService, groupService);

    @Test
    void ordinaryMessagesDoNotStartAgents() {
        listener.onMessageSent(event(List.of()));
        verifyNoInteractions(agents, messages);
    }

    @Test
    void userMentionsDoNotStartAgents() {
        Mention mention = new Mention();
        mention.setType("USER");
        mention.setId(TestIds.next());
        listener.onMessageSent(event(List.of(mention)));
        verifyNoInteractions(agents);
    }

    @Test
    void eachMentionedGroupAgentRunsOnceForDuplicateMentions() {
        Long firstAgent = TestIds.next(), secondAgent = TestIds.next();
        Mention first = mention(firstAgent);
        MessageSentEvent event = event(List.of(first, first, mention(secondAgent)));
        MessageEntity message = new MessageEntity();
        message.setContent("{\"text\":\"你好\"}");
        when(messages.selectById(event.messageId())).thenReturn(message);
        when(messageService.agentInstruction(message)).thenReturn("你好");
        when(groupService.groupAgentIds(event.groupId()))
                .thenReturn(List.of(firstAgent, secondAgent));

        listener.onMessageSent(event);

        verify(agents).replyToMention(event, firstAgent, "你好");
        verify(agents).replyToMention(event, secondAgent, "你好");
        verifyNoMoreInteractions(agents);
    }

    @Test
    void oneFailingAgentDoesNotSkipTheRemainingMentions() {
        Long failingAgent = TestIds.next(), otherAgent = TestIds.next();
        MessageSentEvent event = event(List.of(mention(failingAgent), mention(otherAgent)));
        MessageEntity message = new MessageEntity();
        message.setContent("{\"text\":\"你好\"}");
        when(messages.selectById(event.messageId())).thenReturn(message);
        when(messageService.agentInstruction(message)).thenReturn("你好");
        when(groupService.groupAgentIds(event.groupId()))
                .thenReturn(List.of(failingAgent, otherAgent));
        doThrow(new IllegalStateException("沙箱不可用"))
                .when(agents)
                .replyToMention(event, failingAgent, "你好");

        listener.onMessageSent(event);

        // 一个 Agent 起不来不能连坐其余被提及的 Agent。
        verify(agents).replyToMention(event, otherAgent, "你好");
    }

    @Test
    void agentOutsideTheGroupDoesNotRun() {
        Long groupAgent = TestIds.next(), foreignAgent = TestIds.next();
        MessageSentEvent event = event(List.of(mention(foreignAgent)));
        MessageEntity message = new MessageEntity();
        message.setContent("{\"text\":\"你好\"}");
        when(messages.selectById(event.messageId())).thenReturn(message);
        when(groupService.groupAgentIds(event.groupId())).thenReturn(List.of(groupAgent));

        listener.onMessageSent(event);

        verifyNoInteractions(agents);
    }

    private Mention mention(Long agentId) {
        Mention mention = new Mention();
        mention.setType("AGENT");
        mention.setId(agentId);
        return mention;
    }

    private MessageSentEvent event(List<Mention> mentions) {
        return new MessageSentEvent(
                TestIds.next(), TestIds.next(), TestIds.next(), TestIds.next(), mentions);
    }
}
