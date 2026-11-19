package qg.qgent.agent.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.HttpStatus;
import qg.qgent.agent.dto.AgentApplicationRequest;
import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.agent.service.AgentApplicationService;
import qg.qgent.common.web.ApiException;
import qg.qgent.harness.tool.ToolErrors;
import qg.qgent.harness.tool.ToolResult;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 自定义 Agent 的申请与审批工具，只装配给主 Agent。
 * <p>
 * 申请由当前项目与操作者绑定；审批按模型传入的申请 ID 进行，服务端核验真实发送者的管理员身份、
 * 申请所属项目与状态，模型只能选择审批哪条申请，不能让它绕过核验生效。
 */
public class AgentApplicationTools {

    private final AgentApplicationService applications;
    private final Long actor;
    private final Long projectId;

    public AgentApplicationTools(AgentApplicationService applications, Long actor, Long projectId) {
        this.applications = applications;
        this.actor = actor;
        this.projectId = projectId;
    }

    /**
     * 帮成员起草并向项目管理员提交自定义 Agent 申请；提交后仍待审批，不能使用。
     */
    @Tool(
        name = "submit_agent_application",
        description =
            "帮当前用户向项目管理员申请创建自定义 Agent。"
                + "先根据对话整理名称、职责描述和系统提示词，向用户确认后再提交；头像 URL 可省略。"
                + "提交后用普通文本把名称、职责和系统提示词摘要告诉用户，说明申请待项目管理员审批，"
                + "通过后才能邀请入群；管理员 @你 表示同意或拒绝时再审批。")
    public ToolResult submitAgentApplication(
        @ToolParam(description = "Agent 名称，最多 120 字符") String name,
        @ToolParam(description = "Agent 职责描述，可省略", required = false) String description,
        @ToolParam(description = "Agent 系统提示词，说明它的身份、职责和协作方式") String systemPrompt,
        @ToolParam(description = "可选头像 URL；没有就省略", required = false) String avatarUrl) {
        if (name == null || name.isBlank() || systemPrompt == null || systemPrompt.isBlank()) {
            return ToolResult.error("INVALID_ARGUMENT", "name 和 systemPrompt 不能为空");
        }
        return call(() -> {
            AgentApplicationRequest request = new AgentApplicationRequest();
            request.setName(name);
            request.setDescription(description);
            request.setSystemPrompt(systemPrompt);
            request.setAvatarUrl(avatarUrl == null || avatarUrl.isBlank() ? null : avatarUrl);
            AgentEntity application = applications.apply(actor, projectId, request);
            ToolResult result = ToolResult.ok();
            result.put("applicationId", application.getId().toString());
            result.put("status", application.getStatus());
            return result;
        });
    }

    /**
     * 取出项目里全部待审批申请，交给模型自己组织给管理员的说明。
     * <p>
     * 只读：申请与审批都不依赖哪条消息先发出，管理员按 applicationId 审批。
     */
    @Tool(
        name = "list_pending_agent_applications",
        description =
            "取出本项目全部待审批的自定义 Agent 申请，每项含 applicationId、名称、职责和系统提示词。"
                + "在项目默认群用普通文本 @项目管理员，说明名称、职责和提示词摘要并问是否同意；"
                + "不要声称申请已经生效。")
    public ToolResult listPendingAgentApplications() {
        return call(() -> {
            List<AgentEntity> pending = applications.pendingApplications(actor, projectId);
            ToolResult result = ToolResult.ok();
            result.put(
                "applications", pending.stream().map(AgentApplicationTools::summary).toList());
            return result;
        });
    }

    /**
     * 管理员同意后按申请 ID 审批通过；管理员身份与申请状态由服务端核验。
     */
    @Tool(
        name = "approve_agent_application",
        description =
            "项目管理员 @你 表示同意某条待审批申请后审批通过。"
                + "传入该申请的 applicationId；服务端核验真实发送者的项目管理员身份与申请状态，"
                + "失败时如实说明审批未生效。")
    public ToolResult approveAgentApplication(
        @ToolParam(description = "待审批申请的 applicationId，来自 list_pending_agent_applications")
        String applicationId) {
        return call(() -> {
            AgentEntity agent = applications.approve(actor, projectId, parseId(applicationId));
            ToolResult result = ToolResult.ok();
            result.put("applicationId", agent.getId().toString());
            result.put("status", agent.getStatus());
            return result;
        });
    }

    /**
     * 管理员拒绝申请；被拒绝的申请不能激活，也不再占用项目 Agent 数量上限。
     */
    @Tool(
        name = "reject_agent_application",
        description = "项目管理员 @你 表示拒绝某条待审批申请后拒绝该申请。" + "传入该申请的 applicationId；服务端同样核验真实管理员与申请状态。")
    public ToolResult rejectAgentApplication(
        @ToolParam(description = "待审批申请的 applicationId，来自 list_pending_agent_applications")
        String applicationId) {
        return call(() -> {
            AgentEntity agent = applications.reject(actor, projectId, parseId(applicationId));
            ToolResult result = ToolResult.ok();
            result.put("applicationId", agent.getId().toString());
            result.put("status", agent.getStatus());
            return result;
        });
    }

    private static Map<String, String> summary(AgentEntity application) {
        return Map.of(
            "applicationId",
            application.getId().toString(),
            "name",
            application.getName(),
            "description",
            application.getDescription() == null ? "" : application.getDescription(),
            "systemPrompt",
            application.getSystemPrompt() == null ? "" : application.getSystemPrompt());
    }

    private Long parseId(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST, "AGENT_APPLICATION_ID_INVALID", "申请 ID 无效");
        }
    }

    private ToolResult call(Supplier<ToolResult> action) {
        return ToolErrors.call("AGENT_APPLICATION_UNAVAILABLE", "Agent 申请操作失败", action);
    }
}
