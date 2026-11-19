package qg.qgent.project.service;

import qg.qgent.project.dto.ProjectResponse;
import qg.qgent.project.entity.ProjectEntity;
import qg.qgent.project.mapper.view.ProjectMembershipView;

/**
 * 项目响应的唯一组装点；成员数由调用方提供，列表查询自带聚合。
 */
public final class ProjectViews {
    private ProjectViews() {
    }

    public static ProjectResponse response(ProjectEntity project, String role, Long memberCount) {
        ProjectResponse response =
            new ProjectResponse(
                project.getId().toString(),
                project.getTeamId().toString(),
                project.getName(),
                project.getDescription(),
                role,
                project.getStatus(),
                memberCount);
        response.setAvatarUrl(project.getAvatarUrl());
        return response;
    }

    public static ProjectResponse response(ProjectMembershipView row) {
        ProjectResponse response =
            new ProjectResponse(
                row.getId().toString(),
                row.getTeamId().toString(),
                row.getName(),
                row.getDescription(),
                row.getRole(),
                row.getStatus(),
                row.getMemberCount());
        response.setAvatarUrl(row.getAvatarUrl());
        return response;
    }
}
