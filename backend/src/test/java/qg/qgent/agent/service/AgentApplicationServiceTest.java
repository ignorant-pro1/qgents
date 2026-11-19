package qg.qgent.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import qg.qgent.agent.dto.AgentApplicationRequest;
import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.agent.mapper.AgentMapper;
import qg.qgent.common.web.ApiException;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.support.TestIds;

/** 自定义 Agent 申请：成员提交、项目内数量上限、项目管理员按申请 ID 审批。 */
class AgentApplicationServiceTest {

    private static final int MAX_AGENTS = 3;

    private final AgentMapper agents = mock(AgentMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final AgentApplicationService service =
            new AgentApplicationService(agents, access, projects, MAX_AGENTS);

    @Test
    void memberApplicationIsStoredAsPendingAgentWithItsOwnPrompt() {
        Long actor = TestIds.next(), project = TestIds.next();

        AgentEntity application = service.apply(actor, project, request("  评审助手 "));

        assertThat(application.getStatus()).isEqualTo(AgentEntity.STATUS_PENDING);
        assertThat(application.getProjectId()).isEqualTo(project);
        assertThat(application.getName()).isEqualTo("评审助手");
        assertThat(application.getDescription()).isEqualTo("负责代码评审");
        assertThat(application.getSystemPrompt()).isEqualTo("你负责评审 Pull Request");
        assertThat(application.getAvatarUrl()).isEqualTo("https://cdn.example.com/review.png");
        assertThat(application.getProposedBy()).isEqualTo(actor);
        assertThat(application.getDefaultAgent()).isFalse();
        verify(agents).insert(application);
        // 计数与插入都在项目行锁内：并发申请无法同时通过上限检查。
        var order = inOrder(projects, agents);
        order.verify(projects).selectByIdForUpdate(project);
        order.verify(agents).countCountedByProject(project);
        order.verify(agents).insert(application);
    }

    @Test
    void applicationIsRefusedOnceTheProjectAgentLimitIsReached() {
        Long actor = TestIds.next(), project = TestIds.next();
        when(agents.countCountedByProject(project)).thenReturn(MAX_AGENTS);

        ApiException error =
                assertThatApiError(() -> service.apply(actor, project, request("评审助手")));

        assertThat(error.code()).isEqualTo("AGENT_LIMIT_REACHED");
        verify(agents, never()).insert(any(AgentEntity.class));
    }

    @Test
    void applicationWithoutNameOrSystemPromptIsRejected() {
        Long actor = TestIds.next(), project = TestIds.next();

        assertThat(assertThatApiError(() -> service.apply(actor, project, request("  "))).code())
                .isEqualTo("AGENT_APPLICATION_INVALID");
        AgentApplicationRequest withoutPrompt = request("评审助手");
        withoutPrompt.setSystemPrompt(null);
        assertThat(assertThatApiError(() -> service.apply(actor, project, withoutPrompt)).code())
                .isEqualTo("AGENT_APPLICATION_INVALID");
        verify(agents, never()).insert(any(AgentEntity.class));
    }

    @Test
    void descriptionAndAvatarWithUnreasonableLengthAreRejected() {
        Long actor = TestIds.next(), project = TestIds.next();
        AgentApplicationRequest tooLongDescription = request("评审助手");
        tooLongDescription.setDescription("描".repeat(2001));
        AgentApplicationRequest tooLongAvatar = request("评审助手");
        tooLongAvatar.setAvatarUrl("https://cdn.example.com/" + "a".repeat(2048));

        assertThat(
                        assertThatApiError(() -> service.apply(actor, project, tooLongDescription))
                                .code())
                .isEqualTo("AGENT_APPLICATION_INVALID");
        assertThat(assertThatApiError(() -> service.apply(actor, project, tooLongAvatar)).code())
                .isEqualTo("AGENT_APPLICATION_INVALID");
        verify(agents, never()).insert(any(AgentEntity.class));
    }

    @Test
    void projectMemberOutsideTheProjectCannotApply() {
        Long actor = TestIds.next(), project = TestIds.next();
        doThrow(new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在或不可见"))
                .when(access)
                .requireProjectMember(project, actor);

        assertThat(assertThatApiError(() -> service.apply(actor, project, request("评审助手"))).code())
                .isEqualTo("PROJECT_NOT_FOUND");
        verify(agents, never()).insert(any(AgentEntity.class));
        verify(projects, never()).selectByIdForUpdate(any());
    }

    @Test
    void enabledAgentsAreListedForGroupInvites() {
        Long actor = TestIds.next(), project = TestIds.next();
        AgentEntity defaultAgent = agent(TestIds.next(), project, AgentEntity.STATUS_ACTIVE);
        when(agents.selectActiveByProject(project)).thenReturn(List.of(defaultAgent));

        assertThat(service.enabledAgents(actor, project)).containsExactly(defaultAgent);
        verify(access).requireProjectMember(project, actor);
    }

    @Test
    void adminListsPendingApplicationsOfTheProject() {
        Long admin = TestIds.next(), project = TestIds.next();
        AgentEntity pending = agent(TestIds.next(), project, AgentEntity.STATUS_PENDING);
        when(agents.selectPendingByProject(project)).thenReturn(List.of(pending));

        assertThat(service.pendingApplications(admin, project)).containsExactly(pending);
        verify(access).requireProjectAdmin(project, admin);
    }

    @Test
    void pendingApplicationsAreOnlyVisibleToProjectAdmins() {
        Long member = TestIds.next(), project = TestIds.next();
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "PROJECT_ADMIN_REQUIRED", "需要项目 Admin 权限"))
                .when(access)
                .requireProjectAdmin(project, member);

        assertThat(assertThatApiError(() -> service.pendingApplications(member, project)).code())
                .isEqualTo("PROJECT_ADMIN_REQUIRED");
        verify(agents, never()).selectPendingByProject(any());
    }

    @Test
    void adminApprovalOfTheApplicationByIdActivatesIt() {
        Long admin = TestIds.next(), project = TestIds.next();
        AgentEntity application = pendingApplication(project);
        AgentEntity activated = agent(application.getId(), project, AgentEntity.STATUS_ACTIVE);
        when(agents.selectById(application.getId())).thenReturn(application, activated);
        when(agents.activate(application.getId(), admin)).thenReturn(1);

        assertThat(service.approve(admin, project, application.getId())).isSameAs(activated);
        verify(access).requireProjectAdmin(project, admin);
        verify(agents).activate(application.getId(), admin);
    }

    @Test
    void plainMemberCannotApprove() {
        Long member = TestIds.next(), project = TestIds.next();
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "PROJECT_ADMIN_REQUIRED", "需要项目 Admin 权限"))
                .when(access)
                .requireProjectAdmin(project, member);

        assertThat(
                        assertThatApiError(() -> service.approve(member, project, TestIds.next()))
                                .code())
                .isEqualTo("PROJECT_ADMIN_REQUIRED");
        verify(agents, never()).selectById(any());
        verify(agents, never()).activate(any(), any());
    }

    @Test
    void unknownApplicationIdIsNotFound() {
        Long admin = TestIds.next(), project = TestIds.next();
        Long unknown = TestIds.next();
        when(agents.selectById(unknown)).thenReturn(null);

        assertThat(assertThatApiError(() -> service.approve(admin, project, unknown)).code())
                .isEqualTo("AGENT_APPLICATION_NOT_FOUND");
        assertThat(assertThatApiError(() -> service.reject(admin, project, unknown)).code())
                .isEqualTo("AGENT_APPLICATION_NOT_FOUND");
        verify(agents, never()).activate(any(), any());
        verify(agents, never()).reject(any(), any());
    }

    @Test
    void applicationOfAnotherProjectCannotBeApprovedHere() {
        Long admin = TestIds.next(), project = TestIds.next();
        AgentEntity foreign = pendingApplication(TestIds.next());
        when(agents.selectById(foreign.getId())).thenReturn(foreign);

        assertThat(
                        assertThatApiError(() -> service.approve(admin, project, foreign.getId()))
                                .code())
                .isEqualTo("AGENT_APPLICATION_NOT_FOUND");
        verify(agents, never()).activate(any(), any());
    }

    @Test
    void repeatedApprovalIsRejectedWithoutASecondActivation() {
        Long admin = TestIds.next(), project = TestIds.next();
        AgentEntity application = pendingApplication(project);
        application.setStatus(AgentEntity.STATUS_ACTIVE);
        when(agents.selectById(application.getId())).thenReturn(application);

        assertThat(
                        assertThatApiError(
                                        () -> service.approve(admin, project, application.getId()))
                                .code())
                .isEqualTo("AGENT_APPLICATION_NOT_PENDING");
        verify(agents, never()).activate(any(), any());
    }

    @Test
    void concurrentApprovalLosesWhenTheRowIsNoLongerPending() {
        Long admin = TestIds.next(), project = TestIds.next();
        AgentEntity application = pendingApplication(project);
        when(agents.selectById(application.getId())).thenReturn(application);
        // 另一个审批先提交：条件更新影响 0 行，本次不能再次激活。
        when(agents.activate(application.getId(), admin)).thenReturn(0);

        assertThat(
                        assertThatApiError(
                                        () -> service.approve(admin, project, application.getId()))
                                .code())
                .isEqualTo("AGENT_APPLICATION_NOT_PENDING");
    }

    @Test
    void adminRejectionFreesTheApplicationWithoutActivatingIt() {
        Long admin = TestIds.next(), project = TestIds.next();
        AgentEntity application = pendingApplication(project);
        AgentEntity rejected = agent(application.getId(), project, AgentEntity.STATUS_REJECTED);
        when(agents.selectById(application.getId())).thenReturn(application, rejected);
        when(agents.reject(application.getId(), admin)).thenReturn(1);

        assertThat(service.reject(admin, project, application.getId())).isSameAs(rejected);
        verify(agents).reject(application.getId(), admin);
        verify(agents, never()).activate(any(), any());
    }

    @Test
    void rejectedApplicationCannotBeApprovedAfterwards() {
        Long admin = TestIds.next(), project = TestIds.next();
        AgentEntity application = pendingApplication(project);
        application.setStatus(AgentEntity.STATUS_REJECTED);
        when(agents.selectById(application.getId())).thenReturn(application);

        assertThat(
                        assertThatApiError(
                                        () -> service.approve(admin, project, application.getId()))
                                .code())
                .isEqualTo("AGENT_APPLICATION_NOT_PENDING");
        assertThat(
                        assertThatApiError(
                                        () -> service.reject(admin, project, application.getId()))
                                .code())
                .isEqualTo("AGENT_APPLICATION_NOT_PENDING");
        verify(agents, never()).activate(any(), any());
        verify(agents, never()).reject(any(), any());
    }

    private AgentEntity pendingApplication(Long projectId) {
        AgentEntity application = agent(TestIds.next(), projectId, AgentEntity.STATUS_PENDING);
        application.setProposedBy(TestIds.next());
        return application;
    }

    private AgentEntity agent(Long agentId, Long projectId, String status) {
        AgentEntity agent = new AgentEntity();
        agent.setId(agentId);
        agent.setProjectId(projectId);
        agent.setStatus(status);
        agent.setDefaultAgent(false);
        return agent;
    }

    private AgentApplicationRequest request(String name) {
        AgentApplicationRequest request = new AgentApplicationRequest();
        request.setName(name);
        request.setDescription(" 负责代码评审 ");
        request.setSystemPrompt("你负责评审 Pull Request");
        request.setAvatarUrl("https://cdn.example.com/review.png");
        return request;
    }

    private ApiException assertThatApiError(org.junit.jupiter.api.function.Executable action) {
        return assertThrows(ApiException.class, action);
    }
}
