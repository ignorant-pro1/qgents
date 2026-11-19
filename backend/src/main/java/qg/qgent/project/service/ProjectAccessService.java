package qg.qgent.project.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import qg.qgent.common.web.ApiException;
import qg.qgent.project.entity.ProjectEntity;
import qg.qgent.project.entity.ProjectMemberEntity;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.mapper.ProjectMemberMapper;
import qg.qgent.team.entity.TeamEntity;
import qg.qgent.team.entity.TeamMemberEntity;
import qg.qgent.team.mapper.TeamMapper;
import qg.qgent.team.mapper.TeamMemberMapper;

/**
 * 服务端团队与项目访问边界，不信任客户端提交的角色或用户标识。
 */
@Service
@RequiredArgsConstructor
public class ProjectAccessService {
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final TeamMapper teamMapper;
    private final TeamMemberMapper teamMemberMapper;

    public String requireProjectMember(Long projectId, Long userId) {
        ProjectEntity project = requireProject(projectId);
        return requireAccess(project, userId);
    }

    public void requireProjectAdmin(Long projectId, Long userId) {
        requireAdmin(requireProject(projectId), userId);
    }

    /**
     * 对已加锁项目执行不限状态的 Admin 校验，供状态错误前的防泄漏授权使用。
     */
    public void requireProjectAdminAnyState(ProjectEntity project, Long userId) {
        requireAdmin(project, userId);
    }

    public String requireAccess(ProjectEntity project, Long userId) {
        // canonical Team Owner 是跨项目兜底管理员，不要求存在 project_members 行。
        if ("TEAM_OWNER".equals(teamRole(project.getTeamId(), userId))) {
            return "PROJECT_ADMIN";
        }
        ProjectMemberEntity member =
            projectMemberMapper.selectByProjectAndUser(project.getId(), userId);
        if (member == null) {
            throw notFound();
        }
        return member.getRole();
    }

    public void requireAdmin(ProjectEntity project, Long userId) {
        if ("TEAM_OWNER".equals(teamRole(project.getTeamId(), userId))) {
            return;
        }
        ProjectMemberEntity member =
            projectMemberMapper.selectByProjectAndUser(project.getId(), userId);
        if (member != null && "PROJECT_ADMIN".equals(member.getRole())) {
            return;
        }
        if (member == null) {
            throw notFound();
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "PROJECT_ADMIN_REQUIRED", "需要项目 Admin 权限");
    }

    /**
     * 团队生效角色；同时核对 teams.owner_user_id 与成员角色，额外 TEAM_OWNER 不能获得兜底权限。
     */
    public static String effectiveTeamRole(Long ownerUserId, Long userId, String storedRole) {
        if (ownerUserId != null && ownerUserId.equals(userId) && "TEAM_OWNER".equals(storedRole)) {
            return "TEAM_OWNER";
        }
        return "TEAM_ADMIN".equals(storedRole) ? "TEAM_ADMIN" : "TEAM_MEMBER";
    }

    /**
     * 已读入团队行时的生效角色；非成员返回 null。
     */
    public String teamRole(TeamEntity team, Long userId) {
        TeamMemberEntity member = teamMemberMapper.selectByTeamAndUser(team.getId(), userId);
        return member == null
            ? null
            : effectiveTeamRole(team.getOwnerUserId(), userId, member.getRole());
    }

    public String teamRole(Long teamId, Long userId) {
        TeamEntity team = teamMapper.selectById(teamId);
        return team == null ? null : teamRole(team, userId);
    }

    /**
     * 团队成员记录；不是成员时按 404 处理，避免枚举团队。
     */
    public TeamMemberEntity requireTeamMemberRow(Long teamId, Long userId) {
        TeamMemberEntity member = teamMemberMapper.selectByTeamAndUser(teamId, userId);
        if (member == null) {
            throw teamNotFound();
        }
        return member;
    }

    /**
     * 校验调用者为团队成员，返回生效角色。
     */
    public String requireTeamMemberRole(TeamEntity team, Long userId) {
        String role = teamRole(team, userId);
        if (role == null) {
            throw teamNotFound();
        }
        return role;
    }

    /**
     * 校验调用者为团队所有者或管理员，返回生效角色。
     */
    public String requireTeamManager(TeamEntity team, Long userId) {
        String role = requireTeamMemberRole(team, userId);
        if ("TEAM_OWNER".equals(role) || "TEAM_ADMIN".equals(role)) {
            return role;
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "TEAM_ADMIN_REQUIRED", "需要团队所有者或管理员权限");
    }

    /**
     * 校验调用者为团队所有者。
     */
    public void requireTeamOwner(TeamEntity team, Long userId) {
        if (!"TEAM_OWNER".equals(requireTeamMemberRole(team, userId))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "TEAM_OWNER_REQUIRED", "需要团队所有者权限");
        }
    }

    /**
     * 判断当前用户是否为 canonical Team Owner；供能力派生使用。
     */
    public boolean isCanonicalTeamOwner(Long teamId, Long userId) {
        return "TEAM_OWNER".equals(teamRole(teamId, userId));
    }

    /**
     * 校验调用者为团队成员（团队不存在或非成员返回 404，防枚举）。
     */
    public void requireTeamMember(Long teamId, Long userId) {
        requireTeamMemberRow(teamId, userId);
    }

    /**
     * 判断当前用户是否为项目 Admin（含 canonical Team Owner 兜底）。
     */
    public boolean isProjectAdmin(Long projectId, Long userId) {
        try {
            requireProjectAdmin(projectId, userId);
            return true;
        } catch (ApiException e) {
            return false;
        }
    }

    private ProjectEntity requireProject(Long projectId) {
        ProjectEntity project = projectMapper.selectById(projectId);
        if (project == null || !"ACTIVE".equals(project.getStatus())) {
            throw notFound();
        }
        return project;
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在或不可见");
    }

    private ApiException teamNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "TEAM_RESOURCE_NOT_FOUND", "团队资源不存在或不可见");
    }
}
