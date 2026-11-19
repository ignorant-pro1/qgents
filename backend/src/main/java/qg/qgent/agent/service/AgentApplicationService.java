package qg.qgent.agent.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.agent.dto.AgentApplicationRequest;
import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.agent.mapper.AgentMapper;
import qg.qgent.common.web.ApiException;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.service.ProjectAccessService;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 自定义 Agent 申请与审批：任一项目成员提交，项目管理员按申请 ID 审批后生效。
 * <p>
 * 服务端按申请 ID 定位记录，并核验真实发送者的项目管理员身份、申请所属项目与申请状态。
 * 待审批申请也计入项目 Agent 数量上限，被拒绝的申请释放名额；申请与计数在同一项目行锁内完成。
 */
@Service
public class AgentApplicationService {

    private final AgentMapper agents;
    private final ProjectAccessService access;
    private final ProjectMapper projects;
    private final int maxAgentsPerProject;

    public AgentApplicationService(
        AgentMapper agents,
        ProjectAccessService access,
        ProjectMapper projects,
        @Value("${app.agent.max-per-project:10}") int maxAgentsPerProject) {
        if (maxAgentsPerProject < 1) throw new IllegalArgumentException("max-per-project 必须大于 0");
        this.agents = agents;
        this.access = access;
        this.projects = projects;
        this.maxAgentsPerProject = maxAgentsPerProject;
    }

    /**
     * 提交自定义 Agent 申请，保存为待审批记录，此时还不能邀请入群或运行。
     */
    @Transactional
    public AgentEntity apply(Long actor, Long projectId, AgentApplicationRequest request) {
        // 需要是项目成员
        access.requireProjectMember(projectId, actor);
        // 获取参数
        String name = required(request.getName(), 120, "Agent 申请需要 1～120 字符的名称");
        String systemPrompt =
            required(request.getSystemPrompt(), 20000, "Agent 申请需要不超过 20000 字符的系统提示词");
        String description = optional(request.getDescription(), 2000, "Agent 申请的描述不能超过 2000 字符");
        String avatarUrl = optional(request.getAvatarUrl(), 2048, "Agent 申请的头像地址不能超过 2048 字符");
        // 项目行锁串行化同项目的申请；计数本身是加锁读，才看得到刚提交的申请。
        projects.selectByIdForUpdate(projectId);
        if (agents.countCountedByProject(projectId) >= maxAgentsPerProject) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "AGENT_LIMIT_REACHED",
                "项目内 Agent 已达上限 " + maxAgentsPerProject + "，请联系项目管理员清理申请");
        }
        // 添加记录
        AgentEntity application = new AgentEntity();
        application.setProjectId(projectId);
        application.setName(name);
        application.setDescription(description);
        application.setSystemPrompt(systemPrompt);
        application.setAvatarUrl(avatarUrl);
        application.setDefaultAgent(false);
        application.setStatus(AgentEntity.STATUS_PENDING);
        application.setProposedBy(actor);
        application.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        agents.insert(application);
        return application;
    }

    /**
     * 项目内已启用的 Agent，供群设置邀请成员使用。
     */
    public List<AgentEntity> enabledAgents(Long actor, Long projectId) {
        access.requireProjectMember(projectId, actor);
        return agents.selectActiveByProject(projectId);
    }

    /**
     * 项目待审批的申请：只有项目管理员需要处理队列，申请原文不外泄给其他成员。
     */
    public List<AgentEntity> pendingApplications(Long actor, Long projectId) {
        access.requireProjectAdmin(projectId, actor);
        return agents.selectPendingByProject(projectId);
    }

    /**
     * 管理员按申请 ID 审批通过；身份、所属项目与申请状态由服务端核验。
     */
    @Transactional
    public AgentEntity approve(Long actor, Long projectId, Long applicationId) {
        access.requireProjectAdmin(projectId, actor);
        requirePendingApplication(projectId, applicationId);
        if (agents.activate(applicationId, actor) == 0) {
            throw notPending();
        }
        return agents.selectById(applicationId);
    }

    /**
     * 管理员拒绝申请：被拒绝的申请不能激活，也不再占用数量上限。
     */
    @Transactional
    public AgentEntity reject(Long actor, Long projectId, Long applicationId) {
        access.requireProjectAdmin(projectId, actor);
        requirePendingApplication(projectId, applicationId);
        if (agents.reject(applicationId, actor) == 0) {
            throw notPending();
        }
        return agents.selectById(applicationId);
    }

    private void requirePendingApplication(Long projectId, Long applicationId) {
        AgentEntity application = agents.selectById(applicationId);
        if (application == null || !projectId.equals(application.getProjectId())) {
            throw applicationNotFound();
        }
        if (!AgentEntity.STATUS_PENDING.equals(application.getStatus())) {
            throw notPending();
        }
    }

    private String required(String value, int maxLength, String message) {
        String text = trimmed(value);
        if (text == null || text.length() > maxLength) {
            throw new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY, "AGENT_APPLICATION_INVALID", message);
        }
        return text;
    }

    private String optional(String value, int maxLength, String message) {
        String text = trimmed(value);
        if (text != null && text.length() > maxLength) {
            throw new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY, "AGENT_APPLICATION_INVALID", message);
        }
        return text;
    }

    private String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ApiException applicationNotFound() {
        return new ApiException(
            HttpStatus.NOT_FOUND, "AGENT_APPLICATION_NOT_FOUND", "申请不存在或不属于当前项目");
    }

    private ApiException notPending() {
        return new ApiException(
            HttpStatus.CONFLICT, "AGENT_APPLICATION_NOT_PENDING", "该申请已处理，不能重复审批");
    }
}
