package qg.qgent.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.agent.mapper.AgentMapper;
import qg.qgent.attachment.service.AttachmentService;
import qg.qgent.chat.dto.Mention;
import qg.qgent.chat.dto.MessageResponse;
import qg.qgent.chat.dto.MessageSendRequest;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.entity.MessageEntity;
import qg.qgent.chat.event.GroupMessageCreatedEvent;
import qg.qgent.chat.event.MessageSentEvent;
import qg.qgent.chat.mapper.ChatGroupMapper;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.common.web.ApiException;
import qg.qgent.notification.service.NotificationService;
import qg.qgent.realtime.service.EventService;
import qg.qgent.support.TestIds;
import qg.qgent.user.entity.UserEntity;
import qg.qgent.user.mapper.UserMapper;

/** 群聊消息的发送、引用与 Agent 身份验证。 */
class MessageServiceTest {

    @BeforeEach
    void initializeMetadataBeforeEachTest() {
        initializeMybatisMetadata();
    }

    private static void initializeMybatisMetadata() {
        if (TableInfoHelper.getTableInfo(ChatGroupEntity.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), "MessageServiceTest"),
                    ChatGroupEntity.class);
        }
        if (TableInfoHelper.getTableInfo(MessageEntity.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), "MessageServiceTest"),
                    MessageEntity.class);
        }
    }

    @Test
    void agentCannotReplyInGroupItHasNotJoined() {
        Long groupId = TestIds.next(), agentId = TestIds.next();
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setProjectId(TestIds.next());
        MessageMapper messages = mock(MessageMapper.class);
        ChatGroupMapper groups = mock(ChatGroupMapper.class);
        GroupService groupService = mock(GroupService.class);
        when(groups.selectOne(any())).thenReturn(group);
        doThrow(
                        new ApiException(
                                HttpStatus.UNPROCESSABLE_ENTITY,
                                "AGENT_NOT_IN_GROUP",
                                "Agent 未加入当前群聊"))
                .when(groupService)
                .requireGroupAgent(groupId, agentId);
        MessageService service =
                new MessageService(
                        messages,
                        groups,
                        mock(UserMapper.class),
                        groupService,
                        new ObjectMapper(),
                        mock(EventService.class),
                        mock(NotificationService.class),
                        new MessageViews(
                                mock(UserMapper.class),
                                mock(AgentMapper.class),
                                mock(AttachmentService.class),
                                new ObjectMapper()),
                        mock(ApplicationEventPublisher.class));
        assertThatThrownBy(() -> service.sendAsAgent(agentId, groupId, new MessageSendRequest()))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("AGENT_NOT_IN_GROUP"));
        verifyNoInteractions(messages);
    }

    @Test
    void replyInAnotherProjectsGroupFailsBeforeWriting() {
        Long groupId = TestIds.next();
        MessageMapper messages = mock(MessageMapper.class);
        ChatGroupMapper groups = mock(ChatGroupMapper.class);
        MessageService service =
                new MessageService(
                        messages,
                        groups,
                        mock(UserMapper.class),
                        mock(GroupService.class),
                        new ObjectMapper(),
                        mock(EventService.class),
                        mock(NotificationService.class),
                        new MessageViews(
                                mock(UserMapper.class),
                                mock(AgentMapper.class),
                                mock(AttachmentService.class),
                                new ObjectMapper()),
                        mock(ApplicationEventPublisher.class));

        assertThatThrownBy(() ->
                        service.sendAsAgent(TestIds.next(), groupId, new MessageSendRequest()))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("GROUP_NOT_FOUND"));
        verifyNoInteractions(messages);
    }

    @Test
    void mentioningAgentOutsideTheGroupIsRejectedBeforeSaving() {
        Long projectId = TestIds.next(),
                groupId = TestIds.next(),
                actor = TestIds.next(),
                agentId = TestIds.next();
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        MessageMapper messages = mock(MessageMapper.class);
        ChatGroupMapper groups = mock(ChatGroupMapper.class);
        GroupService groupService = mock(GroupService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        when(groups.selectOne(any())).thenReturn(group);
        doThrow(
                        new ApiException(
                                HttpStatus.UNPROCESSABLE_ENTITY,
                                "AGENT_NOT_IN_GROUP",
                                "Agent 未加入当前群聊"))
                .when(groupService)
                .requireGroupAgent(groupId, agentId);
        MessageService service =
                new MessageService(
                        messages,
                        groups,
                        mock(UserMapper.class),
                        groupService,
                        new ObjectMapper(),
                        mock(EventService.class),
                        mock(NotificationService.class),
                        new MessageViews(
                                mock(UserMapper.class),
                                mock(AgentMapper.class),
                                mock(AttachmentService.class),
                                new ObjectMapper()),
                        publisher);
        MessageSendRequest request = new MessageSendRequest();
        request.setType("TEXT");
        request.setContent(Map.of("text", "@助手 看下这个"));
        Mention agent = new Mention();
        agent.setType("AGENT");
        agent.setId(agentId);
        request.setMentions(List.of(agent));

        assertThatThrownBy(() -> service.send(actor, projectId, groupId, request))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("AGENT_NOT_IN_GROUP"));
        verify(messages, never()).insert(any(MessageEntity.class));
        verifyNoInteractions(publisher);
    }

    @Test
    void agentReplyCarriesItsOwnIdentityAndStructuredSender() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        Long agentId = TestIds.next();
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        AgentEntity agent = new AgentEntity();
        agent.setId(agentId);
        agent.setName("项目助手");
        AgentMapper agentMapper = mock(AgentMapper.class);
        when(agentMapper.selectBatchIds(any())).thenReturn(List.of(agent));
        MessageMapper messages = mock(MessageMapper.class);
        ChatGroupMapper groups = mock(ChatGroupMapper.class);
        when(groups.selectOne(any())).thenReturn(group);
        when(messages.nextSequence(groupId)).thenReturn(1L);
        AtomicReference<MessageEntity> inserted = new AtomicReference<>();
        when(messages.insert(any(MessageEntity.class)))
                .thenAnswer(invocation -> {
                    inserted.set(invocation.getArgument(0));
                    inserted.get().setId(TestIds.next());
                    return 1;
                });
        when(messages.selectById(any())).thenAnswer(invocation -> inserted.get());
        MessageService service =
                new MessageService(
                        messages,
                        groups,
                        mock(UserMapper.class),
                        mock(GroupService.class),
                        new ObjectMapper(),
                        mock(EventService.class),
                        mock(NotificationService.class),
                        new MessageViews(
                                mock(UserMapper.class),
                                agentMapper,
                                mock(AttachmentService.class),
                                new ObjectMapper()),
                        mock(ApplicationEventPublisher.class));
        MessageSendRequest request = new MessageSendRequest();
        request.setType("TEXT");
        request.setContent(Map.of("text", "任务更新"));

        MessageResponse response = service.sendAsAgent(agentId, groupId, request);

        ArgumentCaptor<MessageEntity> captured = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messages).insert(captured.capture());
        assertThat(captured.getValue().getSenderAgentId()).isEqualTo(agentId);
        assertThat(captured.getValue().getAuthorUserId()).isNull();
        assertThat(response.getSenderType()).isEqualTo("AGENT");
        assertThat(response.getSenderName()).isEqualTo("项目助手");
        assertThat(response.getSenderId()).isEqualTo(agentId.toString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void userMessageRetryDoesNotRepeatAgentOrUserMentionEvents(boolean originallyMentioned) {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        Long actor = TestIds.next();
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        MessageMapper messages = mock(MessageMapper.class);
        ChatGroupMapper groups = mock(ChatGroupMapper.class);
        when(groups.selectOne(any())).thenReturn(group);
        when(messages.nextSequence(groupId)).thenReturn(1L);
        AtomicReference<MessageEntity> stored = new AtomicReference<>();
        when(messages.selectOne(any())).thenAnswer(invocation -> stored.get());
        when(messages.insert(any(MessageEntity.class)))
                .thenAnswer(invocation -> {
                    stored.set(invocation.getArgument(0));
                    stored.get().setId(TestIds.next());
                    return 1;
                });
        when(messages.selectById(any())).thenAnswer(invocation -> stored.get());
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        MessageService service =
                new MessageService(
                        messages,
                        groups,
                        mock(UserMapper.class),
                        mock(GroupService.class),
                        new ObjectMapper(),
                        mock(EventService.class),
                        mock(NotificationService.class),
                        new MessageViews(
                                mock(UserMapper.class),
                                mock(AgentMapper.class),
                                mock(AttachmentService.class),
                                new ObjectMapper()),
                        publisher);
        MessageSendRequest request = new MessageSendRequest();
        request.setType("TEXT");
        request.setContent(Map.of("text", "@编排助手 补齐邮箱登录功能"));
        Mention agent = new Mention();
        Mention user = new Mention();
        user.setType("USER");
        user.setId(TestIds.next());
        request.setMentions(originallyMentioned ? List.of(agent, user) : List.of());
        request.setClientMessageId("message-" + TestIds.next());

        MessageResponse first = service.send(actor, projectId, groupId, request);
        MessageResponse second = service.send(actor, projectId, groupId, request);
        request.setMentions(List.of(agent));
        MessageResponse changedRetry = service.send(actor, projectId, groupId, request);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(changedRetry.getId()).isEqualTo(first.getId());
        verify(messages, times(1)).insert(any(MessageEntity.class));
        // 每条新消息都维护群记忆的活跃状态；幂等重试不再重复发布。
        verify(publisher).publishEvent(new GroupMessageCreatedEvent(projectId, groupId));
        if (!originallyMentioned) {
            verify(publisher, never()).publishEvent(any(MessageSentEvent.class));
            verifyNoMoreInteractions(publisher);
            return;
        }
        MessageSentEvent event =
                new MessageSentEvent(
                        actor,
                        projectId,
                        groupId,
                        Long.valueOf(first.getId()),
                        List.of(agent, user));
        verify(publisher).publishEvent(event);
        verifyNoMoreInteractions(publisher);
    }

    @Test
    void duplicateInsertReturnsExistingMessageWithoutMentionEvent() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        MessageEntity existing = new MessageEntity();
        existing.setId(TestIds.next());
        existing.setGroupId(groupId);
        existing.setSequenceNo(1L);
        existing.setMessageType("TEXT");
        existing.setContent("{\"text\":\"原消息\"}");
        MessageMapper messages = mock(MessageMapper.class);
        ChatGroupMapper groups = mock(ChatGroupMapper.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        when(groups.selectOne(any())).thenReturn(group);
        when(messages.selectOne(any())).thenReturn(null, existing);
        when(messages.nextSequence(groupId)).thenReturn(2L);
        when(messages.insert(any(MessageEntity.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        MessageService service =
                new MessageService(
                        messages,
                        groups,
                        mock(UserMapper.class),
                        mock(GroupService.class),
                        new ObjectMapper(),
                        mock(EventService.class),
                        mock(NotificationService.class),
                        new MessageViews(
                                mock(UserMapper.class),
                                mock(AgentMapper.class),
                                mock(AttachmentService.class),
                                new ObjectMapper()),
                        publisher);
        MessageSendRequest request = new MessageSendRequest();
        request.setType("TEXT");
        request.setContent(Map.of("text", "重试"));
        request.setClientMessageId("duplicate-message");
        Mention agent = new Mention();
        request.setMentions(List.of(agent));

        MessageResponse response = service.send(TestIds.next(), projectId, groupId, request);

        assertThat(response.getId()).isEqualTo(existing.getId().toString());
        verifyNoInteractions(publisher);
    }

    @Test
    void replyStoresOnlyTextAndOriginalMessageId() {
        MessageMapper messages = mock(MessageMapper.class);
        ChatGroupMapper groups = mock(ChatGroupMapper.class);
        ChatGroupEntity group = new ChatGroupEntity();
        Long groupId = TestIds.next();
        Long agentId = TestIds.next();
        group.setId(groupId);
        group.setProjectId(TestIds.next());
        when(groups.selectOne(any())).thenReturn(group);
        when(messages.nextSequence(groupId)).thenReturn(1L);
        AtomicReference<MessageEntity> inserted = new AtomicReference<>();
        when(messages.insert(any(MessageEntity.class)))
                .thenAnswer(invocation -> {
                    inserted.set(invocation.getArgument(0));
                    inserted.get().setId(TestIds.next());
                    return 1;
                });
        when(messages.selectById(any())).thenAnswer(invocation -> inserted.get());
        MessageService service =
                new MessageService(
                        messages,
                        groups,
                        mock(UserMapper.class),
                        mock(GroupService.class),
                        new ObjectMapper(),
                        mock(EventService.class),
                        mock(NotificationService.class),
                        new MessageViews(
                                mock(UserMapper.class),
                                mock(AgentMapper.class),
                                mock(AttachmentService.class),
                                new ObjectMapper()),
                        mock(ApplicationEventPublisher.class));

        MessageSendRequest request = new MessageSendRequest();
        request.setType("TEXT");
        request.setContent(Map.of("text", "这里回复正文"));
        request.setReplyToId(TestIds.next());
        MessageEntity quoted = new MessageEntity();
        quoted.setGroupId(groupId);
        when(messages.selectById(request.getReplyToId())).thenReturn(quoted);

        MessageResponse response = service.sendAsAgent(agentId, groupId, request);

        ArgumentCaptor<MessageEntity> captured = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messages).insert(captured.capture());
        String storedContent = captured.getValue().getContent();
        assertThat(storedContent).isEqualTo("{\"text\":\"这里回复正文\"}");
        assertThat(response.getType()).isEqualTo("TEXT");
        assertThat(response.getReplyToId()).isEqualTo(request.getReplyToId().toString());
        assertThat(response.getContent()).containsOnlyKeys("text");
    }

    @Test
    void plainJsonTextIsNotInterpretedAsAnExecutionEnvelope() {
        MessageMapper messages = mock(MessageMapper.class);
        ChatGroupMapper groups = mock(ChatGroupMapper.class);
        ChatGroupEntity group = new ChatGroupEntity();
        Long groupId = TestIds.next();
        Long agentId = TestIds.next();
        group.setId(groupId);
        group.setProjectId(TestIds.next());
        when(groups.selectOne(any())).thenReturn(group);
        when(messages.nextSequence(groupId)).thenReturn(1L);
        AtomicReference<MessageEntity> inserted = new AtomicReference<>();
        when(messages.insert(any(MessageEntity.class)))
                .thenAnswer(invocation -> {
                    inserted.set(invocation.getArgument(0));
                    inserted.get().setId(TestIds.next());
                    return 1;
                });
        when(messages.selectById(any())).thenAnswer(invocation -> inserted.get());
        MessageService service =
                new MessageService(
                        messages,
                        groups,
                        mock(UserMapper.class),
                        mock(GroupService.class),
                        new ObjectMapper(),
                        mock(EventService.class),
                        mock(NotificationService.class),
                        new MessageViews(
                                mock(UserMapper.class),
                                mock(AgentMapper.class),
                                mock(AttachmentService.class),
                                new ObjectMapper()),
                        mock(ApplicationEventPublisher.class));

        MessageSendRequest request = new MessageSendRequest();
        request.setType("TEXT");
        request.setContent(
                Map.of(
                        "text",
                        "{\"quotedMessageId\":\"example\",\"quotedText\":\"text\",\"replyText\":\"reply\"}"));

        MessageResponse response = service.sendAsAgent(agentId, groupId, request);

        assertThat(response.getContent()).isEqualTo(request.getContent());
        assertThat(response.getReplyToId()).isNull();
    }

    @Test
    void incrementalMessagesAreReadAfterCursorInAscendingOrder() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        Long actor = TestIds.next();
        MessageMapper messages = mock(MessageMapper.class);
        ChatGroupMapper groups = mock(ChatGroupMapper.class);
        GroupService groupService = mock(GroupService.class);
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        when(groups.selectById(groupId)).thenReturn(group);
        MessageEntity first = message(groupId, 11L, "第一条");
        MessageEntity second = message(groupId, 12L, "第二条");
        when(messages.selectAfterSequence(groupId, 10L, 3)).thenReturn(List.of(first, second));

        MessageService service =
                new MessageService(
                        messages,
                        groups,
                        mock(UserMapper.class),
                        groupService,
                        new ObjectMapper(),
                        mock(EventService.class),
                        mock(NotificationService.class),
                        new MessageViews(
                                mock(UserMapper.class),
                                mock(AgentMapper.class),
                                mock(AttachmentService.class),
                                new ObjectMapper()),
                        mock(ApplicationEventPublisher.class));

        var page = service.listAfterSequence(actor, projectId, groupId, 10L, 2);

        assertThat(page.getData())
                .extracting(MessageResponse::getSequence)
                .containsExactly(11L, 12L);
        assertThat(page.getPage().isHasMore()).isFalse();
        verify(groupService).requireGroupMember(projectId, groupId, actor);
        verify(messages).selectAfterSequence(groupId, 10L, 3);
    }

    @Test
    void incrementalMessagesRejectNegativeCursorBeforeQuery() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        MessageMapper messages = mock(MessageMapper.class);
        ChatGroupMapper groups = mock(ChatGroupMapper.class);
        when(groups.selectById(groupId)).thenReturn(group);
        MessageService service =
                new MessageService(
                        messages,
                        groups,
                        mock(UserMapper.class),
                        mock(GroupService.class),
                        new ObjectMapper(),
                        mock(EventService.class),
                        mock(NotificationService.class),
                        new MessageViews(
                                mock(UserMapper.class),
                                mock(AgentMapper.class),
                                mock(AttachmentService.class),
                                new ObjectMapper()),
                        mock(ApplicationEventPublisher.class));

        assertThatThrownBy(() ->
                        service.listAfterSequence(TestIds.next(), projectId, groupId, -1, 100))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("INVALID_MESSAGE_CURSOR"));
        verify(messages, never()).selectAfterSequence(any(), anyLong(), anyInt());
    }

    @Test
    void historyPageBatchesPreviewsAndBindsCursorToGroup() {
        Long projectId = TestIds.next(), groupId = TestIds.next(), actor = TestIds.next();
        MessageMapper messages = mock(MessageMapper.class);
        GroupService groupService = mock(GroupService.class);
        UserMapper users = mock(UserMapper.class);
        AttachmentService attachments = mock(AttachmentService.class);
        MessageEntity first = message(groupId, 12L, "第二条");
        first.setAuthorUserId(actor);
        MessageEntity image = message(groupId, 11L, "");
        image.setMessageType("IMAGE");
        image.setContent("{\"attachmentId\":\"9\"}");
        MessageEntity last = message(groupId, 10L, "第一条");
        when(messages.selectList(any())).thenReturn(List.of(first, image, last));
        UserEntity user = new UserEntity();
        user.setId(actor);
        user.setDisplayName("小王");
        when(users.selectBatchIds(any())).thenReturn(List.of(user));
        when(attachments.messagePreviews(eq(projectId), any()))
                .thenReturn(
                        Map.of(
                                "9",
                                new AttachmentService.MessageAttachmentPreview(
                                        qg.qgent.attachment.service.AttachmentKind.IMAGE)));
        MessageViews views =
                new MessageViews(users, mock(AgentMapper.class), attachments, new ObjectMapper());
        MessageService service =
                new MessageService(
                        messages,
                        mock(ChatGroupMapper.class),
                        users,
                        groupService,
                        new ObjectMapper(),
                        mock(EventService.class),
                        mock(NotificationService.class),
                        views,
                        mock(ApplicationEventPublisher.class));

        var page = service.list(actor, projectId, groupId, null, 2);

        assertThat(page.getData())
                .extracting(MessageResponse::getSequence)
                .containsExactly(12L, 11L);
        assertThat(page.getPage().isHasMore()).isTrue();
        assertThat(page.getData().getFirst().getSenderName()).isEqualTo("小王");
        assertThat(page.getData().get(1).getContent())
                .containsEntry("previewable", true)
                .containsEntry("previewType", "IMAGE");
        verify(attachments).messagePreviews(projectId, java.util.Set.of("9"));
        // 游标绑定群：同一群可续页，换一个群按无效游标拒绝
        service.list(actor, projectId, groupId, page.getPage().getNextCursor(), 2);
        assertThatThrownBy(() ->
                        service.list(
                                actor,
                                projectId,
                                TestIds.next(),
                                page.getPage().getNextCursor(),
                                2))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("INVALID_CURSOR"));
        assertThatThrownBy(() -> service.list(actor, projectId, groupId, null, 0))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("INVALID_PAGE_LIMIT"));
    }

    private MessageEntity message(Long groupId, long sequence, String text) {
        MessageEntity entity = new MessageEntity();
        entity.setId(TestIds.next());
        entity.setGroupId(groupId);
        entity.setSequenceNo(sequence);
        entity.setMessageType("TEXT");
        entity.setContent("{\"text\":\"" + text + "\"}");
        entity.setMentions("[]");
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }
}
