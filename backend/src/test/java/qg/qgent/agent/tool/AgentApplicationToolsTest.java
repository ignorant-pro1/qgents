package qg.qgent.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.http.HttpStatus;
import qg.qgent.agent.dto.AgentApplicationRequest;
import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.agent.service.AgentApplicationService;
import qg.qgent.common.web.ApiException;
import qg.qgent.support.TestIds;

/** Agent 申请与审批工具：申请帮成员起草，待审批列表只读，审批按申请 ID 交给服务端核验。 */
class AgentApplicationToolsTest {

    private final AgentApplicationService applications = mock(AgentApplicationService.class);
    private final Long actor = TestIds.next();
    private final Long projectId = TestIds.next();
    private final AgentApplicationTools tools =
            new AgentApplicationTools(applications, actor, projectId);

    @Test
    void exposesApplicationAndApprovalTools() {
        assertThat(ToolCallbacks.from(tools))
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactlyInAnyOrder(
                        "submit_agent_application",
                        "list_pending_agent_applications",
                        "approve_agent_application",
                        "reject_agent_application");
    }

    @Test
    void submittedApplicationIsStoredForTheCurrentProjectAndWaitsForApproval() {
        AgentEntity application = pending(TestIds.next());
        when(applications.apply(eq(actor), eq(projectId), any(AgentApplicationRequest.class)))
                .thenReturn(application);

        Map<String, Object> result =
                tools.submitAgentApplication("评审助手", "负责代码评审", "你负责评审 Pull Request", "");

        assertThat(result)
                .containsEntry("ok", true)
                .containsEntry("applicationId", application.getId().toString())
                .containsEntry("status", "PENDING");
        verify(applications)
                .apply(
                        eq(actor),
                        eq(projectId),
                        argThat(request ->
                                "评审助手".equals(request.getName())
                                        && "负责代码评审".equals(request.getDescription())
                                        && "你负责评审 Pull Request".equals(request.getSystemPrompt())
                                        && request.getAvatarUrl() == null));
    }

    @Test
    void applicationWithoutNameIsRejectedWithoutTouchingTheService() {
        Map<String, Object> result = tools.submitAgentApplication("  ", null, "提示词", null);

        assertThat(result)
                .containsEntry("ok", false)
                .containsEntry("errorCode", "INVALID_ARGUMENT");
        verify(applications, never()).apply(any(), any(), any());
    }

    @Test
    void pendingApplicationsAreListedWithTheFieldsTheModelNeedsToDescribeThem() {
        AgentEntity first = pending(TestIds.next());
        AgentEntity second = pending(TestIds.next());
        second.setDescription(null);
        when(applications.pendingApplications(actor, projectId)).thenReturn(List.of(first, second));

        Map<String, Object> result = tools.listPendingAgentApplications();

        assertThat(result).containsEntry("ok", true);
        @SuppressWarnings("unchecked")
        List<Map<String, String>> listed = (List<Map<String, String>>) result.get("applications");
        assertThat(listed).hasSize(2);
        assertThat(listed.get(0))
                .containsEntry("applicationId", first.getId().toString())
                .containsEntry("name", "评审助手")
                .containsEntry("description", "负责代码评审")
                .containsEntry("systemPrompt", "你负责评审 Pull Request");
        // 没有描述的申请回报空字符串，模型不用区分 null 与缺失字段。
        assertThat(listed.get(1)).containsEntry("description", "");
    }

    @Test
    void emptyPendingQueueIsListedAsAnEmptyList() {
        when(applications.pendingApplications(actor, projectId)).thenReturn(List.of());

        Map<String, Object> result = tools.listPendingAgentApplications();

        assertThat(result).containsEntry("ok", true).containsEntry("applications", List.of());
    }

    @Test
    void approvalUsesTheApplicationIdAndReturnsTheActivatedAgent() {
        AgentEntity activated = pending(TestIds.next());
        activated.setStatus(AgentEntity.STATUS_ACTIVE);
        when(applications.approve(actor, projectId, activated.getId())).thenReturn(activated);

        Map<String, Object> result = tools.approveAgentApplication(activated.getId().toString());

        assertThat(result)
                .containsEntry("ok", true)
                .containsEntry("status", "ACTIVE")
                .containsEntry("applicationId", activated.getId().toString());
        verify(applications).approve(actor, projectId, activated.getId());
    }

    @Test
    void invalidApplicationIdIsAToolErrorWithoutTouchingTheService() {
        assertThat(tools.approveAgentApplication("评审助手"))
                .containsEntry("ok", false)
                .containsEntry("errorCode", "AGENT_APPLICATION_ID_INVALID");
        assertThat(tools.rejectAgentApplication(" "))
                .containsEntry("ok", false)
                .containsEntry("errorCode", "AGENT_APPLICATION_ID_INVALID");
        verify(applications, never()).approve(any(), any(), any());
        verify(applications, never()).reject(any(), any(), any());
    }

    @Test
    void approvalRejectionBecomesToolError() {
        Long application = TestIds.next();
        when(applications.approve(actor, projectId, application))
                .thenThrow(
                        new ApiException(
                                HttpStatus.FORBIDDEN, "PROJECT_ADMIN_REQUIRED", "需要项目 Admin 权限"));

        Map<String, Object> result = tools.approveAgentApplication(application.toString());

        assertThat(result)
                .containsEntry("ok", false)
                .containsEntry("errorCode", "PROJECT_ADMIN_REQUIRED");
    }

    @Test
    void rejectionUsesTheApplicationIdAndReportsIt() {
        AgentEntity rejected = pending(TestIds.next());
        rejected.setStatus(AgentEntity.STATUS_REJECTED);
        when(applications.reject(actor, projectId, rejected.getId())).thenReturn(rejected);

        Map<String, Object> result = tools.rejectAgentApplication(rejected.getId().toString());

        assertThat(result).containsEntry("ok", true).containsEntry("status", "REJECTED");
        verify(applications).reject(actor, projectId, rejected.getId());
    }

    private AgentEntity pending(Long agentId) {
        AgentEntity application = new AgentEntity();
        application.setId(agentId);
        application.setProjectId(projectId);
        application.setName("评审助手");
        application.setDescription("负责代码评审");
        application.setSystemPrompt("你负责评审 Pull Request");
        application.setStatus(AgentEntity.STATUS_PENDING);
        return application;
    }
}
