package qg.qgent.chat.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import qg.qgent.chat.entity.MessageEntity;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.common.web.ApiException;
import qg.qgent.support.TestIds;

class ChatHistoryServiceTest {
    private final MessageMapper messages = mock(MessageMapper.class);
    private final GroupService groups = mock(GroupService.class);
    private final ChatHistoryService service =
            new ChatHistoryService(messages, groups, new ObjectMapper());

    @Test
    void codeAndAttachmentsUseTheirStructuredContent() {
        Long group = TestIds.next();
        MessageEntity message = new MessageEntity();
        message.setId(TestIds.next());
        message.setGroupId(group);
        message.setMessageType("CODE");
        message.setContent("{\"language\":\"java\",\"code\":\"return 42;\"}");
        when(messages.selectById(message.getId())).thenReturn(message);
        assertThat(
                        service.readChatMessage(
                                        TestIds.next(), TestIds.next(), group, message.getId())
                                .getText())
                .isEqualTo("return 42;");

        String attachmentId = Long.toString(TestIds.next());
        message.setMessageType("IMAGE");
        message.setContent(
                "{\"attachmentId\":\""
                        + attachmentId
                        + "\",\"name\":\"photo.png\","
                        + "\"mediaType\":\"image/png\",\"size\":2048}");
        var context =
                service.readChatMessage(TestIds.next(), TestIds.next(), group, message.getId());
        assertThat(context.getAttachmentId()).isEqualTo(attachmentId);
        assertThat(context.getFileName()).isEqualTo("photo.png");
        assertThat(context.getMediaType()).isEqualTo("image/png");
        assertThat(context.getSize()).isEqualTo(2048L);
        assertThat(context.getText()).isEmpty();
    }

    @Test
    void readingMessagePreservesFullBodyAndReplyRelationship() {
        Long actor = TestIds.next(), project = TestIds.next(), group = TestIds.next();
        Long id = TestIds.next(), original = TestIds.next();
        MessageEntity message = new MessageEntity();
        message.setId(id);
        message.setGroupId(group);
        message.setReplyToMessageId(original);
        message.setMessageType("TEXT");
        message.setContent("{\"text\":\"" + "需求".repeat(500) + "\"}");
        when(messages.selectById(id)).thenReturn(message);
        var result = service.readChatMessage(actor, project, group, id);
        assertThat(result.getId()).isEqualTo(id.toString());
        assertThat(result.getReplyToId()).isEqualTo(original.toString());
        assertThat(result.getText()).isEqualTo("需求".repeat(500));
        verify(groups).requireGroupMember(project, group, actor);
    }

    @Test
    void agentMessagesExposeTheirOwnSenderId() {
        Long group = TestIds.next(), agentId = TestIds.next();
        MessageEntity message = new MessageEntity();
        message.setId(TestIds.next());
        message.setGroupId(group);
        message.setMessageType("TEXT");
        message.setSenderType("AGENT");
        message.setSenderAgentId(agentId);
        message.setContent("{\"text\":\"好的\"}");
        when(messages.selectById(message.getId())).thenReturn(message);

        var context =
                service.readChatMessage(TestIds.next(), TestIds.next(), group, message.getId());

        assertThat(context.getSenderType()).isEqualTo("AGENT");
        assertThat(context.getSenderId()).isEqualTo(agentId.toString());
    }

    @Test
    void corruptedContentFailsWithMessageId() {
        Long id = TestIds.next();
        MessageEntity message = new MessageEntity();
        message.setId(id);
        message.setGroupId(TestIds.next());
        message.setMessageType("TEXT");
        message.setContent("{not json");
        when(messages.selectById(id)).thenReturn(message);

        assertThatThrownBy(() ->
                        service.readChatMessage(
                                TestIds.next(), TestIds.next(), message.getGroupId(), id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(id.toString())
                .hasCauseInstanceOf(Exception.class);
    }

    @Test
    void messageFromAnotherGroupIsNotReturned() {
        Long id = TestIds.next();
        MessageEntity message = new MessageEntity();
        message.setGroupId(TestIds.next());
        when(messages.selectById(id)).thenReturn(message);
        assertThatThrownBy(() ->
                        service.readChatMessage(TestIds.next(), TestIds.next(), TestIds.next(), id))
                .isInstanceOf(ApiException.class);
    }
}
