package qg.qgent.chat.service;

import org.springframework.stereotype.Service;
import qg.qgent.common.web.ApiException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨群读取的权限边界：提问的用户与被调用的 Agent 都必须属于来源群。
 * <p>
 * 不检查源内容到了目的群会给谁看：把内容问到哪个群是提问者自己的选择，由他负责。
 */
@Service
public class CrossGroupAccessService {

    /**
     * 被拒绝的一侧：告诉模型是哪一侧不在群里，由它在回答里如实说明。
     */
    public enum Denial {
        REQUESTER_NOT_IN_GROUP,
        AGENT_NOT_IN_GROUP
    }

    /**
     * 其他群，以及本次运行能否读取它。
     */
    public record CheckedGroup(String id, String title, Denial denial) {
    }

    private final GroupService groups;

    public CrossGroupAccessService(GroupService groups) {
        this.groups = groups;
    }

    /**
     * 允许读取时返回 null。
     */
    public Denial check(Long projectId, Long sourceGroupId, Long actor, Long agentId) {
        try {
            groups.requireGroupMember(projectId, sourceGroupId, actor);
        } catch (ApiException denied) {
            // 群不存在、属于其他项目或用户不在群里都归到同一侧，不让模型据此探测群成员。
            return Denial.REQUESTER_NOT_IN_GROUP;
        }
        return groups.groupAgentIds(sourceGroupId).contains(agentId)
            ? null
            : Denial.AGENT_NOT_IN_GROUP;
    }

    /**
     * 本次运行可以问到的其他群：提问者看得到的群与 Agent 所在的群的并集，本群已在上下文里，不再列出。
     */
    public List<CheckedGroup> otherGroups(
        Long projectId, Long destinationGroupId, Long actor, Long agentId) {
        Map<Long, String> candidates = new LinkedHashMap<>();
        groups.list(actor, projectId)
            .forEach(group -> candidates.put(Long.valueOf(group.getId()), group.getTitle()));
        groups.groupsOfAgent(projectId, agentId)
            .forEach(group -> candidates.putIfAbsent(group.getId(), group.getName()));
        return candidates.entrySet().stream()
            .filter(entry -> !entry.getKey().equals(destinationGroupId))
            .map(entry ->
                new CheckedGroup(
                    entry.getKey().toString(),
                    entry.getValue(),
                    check(projectId, entry.getKey(), actor, agentId)))
            .toList();
    }
}
