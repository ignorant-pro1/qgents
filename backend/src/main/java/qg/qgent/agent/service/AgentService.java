package qg.qgent.agent.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.agent.mapper.AgentMapper;
import qg.qgent.common.web.ApiException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 项目内 Agent 的归属与默认 Agent 初始化；Agent 只属于创建它的项目。
 */
@Service
@RequiredArgsConstructor
public class AgentService {
    public static final String DEFAULT_AGENT_NAME = "Qgent";

    private final AgentMapper agents;

    /**
     * 项目创建时调用；每个项目得到独立的默认 Agent 记录。
     */
    public AgentEntity createDefaultAgent(Long projectId) {
        AgentEntity agent = new AgentEntity();
        agent.setProjectId(projectId);
        agent.setName(DEFAULT_AGENT_NAME);
        agent.setDefaultAgent(true);
        agent.setStatus(AgentEntity.STATUS_ACTIVE);
        agent.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        agents.insert(agent);
        return agent;
    }

    /**
     * 项目创建事务内一定能取到默认 Agent；缺失说明数据已损坏，直接暴露。
     */
    public AgentEntity defaultAgent(Long projectId) {
        AgentEntity agent = agents.selectDefaultAgent(projectId);
        if (agent == null) {
            throw new IllegalStateException("项目缺少默认 Agent, projectId=" + projectId);
        }
        return agent;
    }

    /**
     * 邀请前校验：每个 Agent 都存在、属于当前项目且已启用；未通过审批的申请不能入群。
     */
    public void requireProjectAgents(Long projectId, List<Long> agentIds) {
        if (agentIds.isEmpty()) {
            return;
        }
        List<AgentEntity> found = agents.selectBatchIds(agentIds);
        boolean allAvailable =
            agentIds.stream().distinct().count() == found.size()
                && found.stream()
                .allMatch(agent ->
                    projectId.equals(agent.getProjectId())
                        && AgentEntity.STATUS_ACTIVE.equals(
                        agent.getStatus()));
        if (!allAvailable) {
            throw new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_GROUP_AGENT", "只能邀请本项目已启用的 Agent");
        }
    }

    /**
     * 运行前校验：被提及的 Agent 必须属于本项目且已启用，否则不产生回复。
     */
    public AgentEntity requireActiveProjectAgent(Long projectId, Long agentId) {
        AgentEntity agent = agents.selectById(agentId);
        if (agent == null
            || !projectId.equals(agent.getProjectId())
            || !AgentEntity.STATUS_ACTIVE.equals(agent.getStatus())) {
            throw new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY, "AGENT_NOT_ACTIVE", "Agent 未启用或不属于本项目");
        }
        return agent;
    }
}
