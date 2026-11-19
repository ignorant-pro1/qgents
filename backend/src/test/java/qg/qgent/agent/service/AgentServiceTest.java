package qg.qgent.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.agent.mapper.AgentMapper;
import qg.qgent.common.web.ApiException;
import qg.qgent.support.TestIds;

/** 每个项目拥有自己的默认 Agent；Agent 只属于创建它的项目。 */
class AgentServiceTest {
    private final AgentMapper agents = mock(AgentMapper.class);
    private final AgentService service = new AgentService(agents);

    @Test
    void everyProjectGetsItsOwnDefaultAgent() {
        when(agents.insert(any(AgentEntity.class)))
                .thenAnswer(call -> {
                    call.getArgument(0, AgentEntity.class).setId(TestIds.next());
                    return 1;
                });
        Long firstProject = TestIds.next(), secondProject = TestIds.next();

        AgentEntity first = service.createDefaultAgent(firstProject);
        AgentEntity second = service.createDefaultAgent(secondProject);

        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(first.getProjectId()).isEqualTo(firstProject);
        assertThat(second.getProjectId()).isEqualTo(secondProject);
        assertThat(first.getName()).isEqualTo(AgentService.DEFAULT_AGENT_NAME);
        assertThat(first.getDefaultAgent()).isTrue();
        assertThat(first.getStatus()).isEqualTo(AgentEntity.STATUS_ACTIVE);
    }

    @Test
    void defaultAgentOfAnotherProjectIsNotAcceptedAsProjectAgent() {
        Long ownProject = TestIds.next(), otherProject = TestIds.next(), agentId = TestIds.next();
        AgentEntity foreign = new AgentEntity();
        foreign.setId(agentId);
        foreign.setProjectId(otherProject);
        when(agents.selectBatchIds(List.of(agentId))).thenReturn(List.of(foreign));

        assertThatThrownBy(() -> service.requireProjectAgents(ownProject, List.of(agentId)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("INVALID_GROUP_AGENT"));
    }

    @Test
    void unknownAgentIsRejectedBeforeInvitesAreWritten() {
        when(agents.selectBatchIds(any())).thenReturn(List.of());

        assertThatThrownBy(
                        () -> service.requireProjectAgents(TestIds.next(), List.of(TestIds.next())))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("INVALID_GROUP_AGENT"));
    }

    @Test
    void applicationStillWaitingForApprovalCannotBeInvited() {
        Long project = TestIds.next(), agentId = TestIds.next();
        AgentEntity pending = agent(agentId, project, AgentEntity.STATUS_PENDING);
        when(agents.selectBatchIds(List.of(agentId))).thenReturn(List.of(pending));

        assertThatThrownBy(() -> service.requireProjectAgents(project, List.of(agentId)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("INVALID_GROUP_AGENT"));
    }

    @Test
    void rejectedApplicationCannotBeInvited() {
        Long project = TestIds.next(), agentId = TestIds.next();
        AgentEntity rejected = agent(agentId, project, AgentEntity.STATUS_REJECTED);
        when(agents.selectBatchIds(List.of(agentId))).thenReturn(List.of(rejected));

        assertThatThrownBy(() -> service.requireProjectAgents(project, List.of(agentId)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("INVALID_GROUP_AGENT"));
    }

    @Test
    void onlyAnActiveAgentOfThisProjectCanRun() {
        Long project = TestIds.next(), agentId = TestIds.next();
        AgentEntity active = agent(agentId, project, AgentEntity.STATUS_ACTIVE);
        when(agents.selectById(agentId)).thenReturn(active);

        assertThat(service.requireActiveProjectAgent(project, agentId)).isSameAs(active);
        assertThatThrownBy(() -> service.requireActiveProjectAgent(TestIds.next(), agentId))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("AGENT_NOT_ACTIVE"));

        AgentEntity pending = agent(agentId, project, AgentEntity.STATUS_PENDING);
        when(agents.selectById(agentId)).thenReturn(pending);
        assertThatThrownBy(() -> service.requireActiveProjectAgent(project, agentId))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("AGENT_NOT_ACTIVE"));
    }

    private AgentEntity agent(Long agentId, Long projectId, String status) {
        AgentEntity agent = new AgentEntity();
        agent.setId(agentId);
        agent.setProjectId(projectId);
        agent.setStatus(status);
        return agent;
    }

    @Test
    void projectsDefaultAgentIsLookedUpByProject() {
        Long projectId = TestIds.next();
        AgentEntity agent = new AgentEntity();
        agent.setId(TestIds.next());
        agent.setProjectId(projectId);
        when(agents.selectDefaultAgent(projectId)).thenReturn(agent);

        assertThat(service.defaultAgent(projectId)).isSameAs(agent);
    }

    @Test
    void missingDefaultAgentIsReportedInsteadOfReturningNull() {
        Long projectId = TestIds.next();
        when(agents.selectDefaultAgent(projectId)).thenReturn(null);

        assertThatThrownBy(() -> service.defaultAgent(projectId))
                .isInstanceOf(IllegalStateException.class);
        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        verify(agents).selectDefaultAgent(captor.capture());
        assertThat(captor.getValue()).isEqualTo(projectId);
        verify(agents, never()).insert(any(AgentEntity.class));
    }
}
