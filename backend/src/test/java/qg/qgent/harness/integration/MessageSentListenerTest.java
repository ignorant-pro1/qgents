package qg.qgent.harness.integration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import qg.qgent.chat.dto.Mention;
import qg.qgent.chat.entity.MessageEntity;
import qg.qgent.chat.event.MessageSentEvent;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.chat.service.MessageService;
import qg.qgent.harness.service.AgentRunService;
import qg.qgent.support.TestIds;

class MessageSentListenerTest {
    private final MessageMapper messages = mock(MessageMapper.class);
    private final MessageService messageService = mock(MessageService.class);
    private final AgentRunService runs = mock(AgentRunService.class);
    private final MessageSentListener listener = new MessageSentListener(messages, messageService, runs);

    @Test
    void ordinaryMessagesAndUserMentionsDoNotQueueRuns() {
        listener.onMessageSent(event(List.of()));
        Mention user = mention(TestIds.next());
        user.setType("USER");
        listener.onMessageSent(event(List.of(user)));
        verifyNoInteractions(runs, messages);
    }

    @Test
    void queuesEachMentionedAgentOnce() {
        Long first = TestIds.next(), second = TestIds.next();
        MessageSentEvent event = event(List.of(mention(first), mention(first), mention(second)));
        prepareInstruction(event);

        listener.onMessageSent(event);

        verify(runs).enqueueRoot(first, event, "instruction");
        verify(runs).enqueueRoot(second, event, "instruction");
        verifyNoMoreInteractions(runs);
    }

    @Test
    void queueWriteFailurePropagatesToTheMessageTransaction() {
        Long agent = TestIds.next();
        MessageSentEvent event = event(List.of(mention(agent)));
        prepareInstruction(event);
        doThrow(new IllegalStateException("queue write failed"))
            .when(runs).enqueueRoot(agent, event, "instruction");

        assertThatThrownBy(() -> listener.onMessageSent(event))
            .isInstanceOf(IllegalStateException.class);
    }

    private void prepareInstruction(MessageSentEvent event) {
        var message = new MessageEntity();
        when(messages.selectById(event.messageId())).thenReturn(message);
        when(messageService.agentInstruction(message)).thenReturn("instruction");
    }

    private Mention mention(Long agentId) {
        Mention mention = new Mention();
        mention.setType("AGENT");
        mention.setId(agentId);
        return mention;
    }

    private MessageSentEvent event(List<Mention> mentions) {
        return new MessageSentEvent(TestIds.next(), TestIds.next(), TestIds.next(), TestIds.next(), mentions);
    }
}
