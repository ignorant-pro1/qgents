package qg.qgent.team.service;

import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.team.dto.TeamResponse;
import qg.qgent.team.entity.TeamEntity;
import qg.qgent.team.mapper.view.TeamMembershipView;

/**
 * 团队响应的唯一组装点；成员数由调用方提供，视图内不再逐条统计。
 */
public final class TeamViews {
    private TeamViews() {
    }

    public static TeamResponse response(TeamEntity team, String role, Integer memberCount) {
        TeamResponse response = new TeamResponse(team.getId().toString(), team.getName(), role);
        response.setMemberCount(memberCount);
        response.setDescription(team.getDescription());
        response.setAvatarUrl(team.getAvatarUrl());
        response.setCreatedAt(team.getCreatedAt());
        return response;
    }

    public static TeamResponse response(TeamMembershipView row, Long actor) {
        TeamResponse response =
            new TeamResponse(
                row.getId().toString(),
                row.getName(),
                ProjectAccessService.effectiveTeamRole(
                    row.getOwnerUserId(), actor, row.getRole()));
        response.setMemberCount(row.getMemberCount());
        response.setDescription(row.getDescription());
        response.setAvatarUrl(row.getAvatarUrl());
        response.setCreatedAt(row.getCreatedAt());
        return response;
    }
}
