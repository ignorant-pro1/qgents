package qg.qgent.project.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.agent.service.AgentService;
import qg.qgent.common.dto.KeysetPagination;
import qg.qgent.common.dto.PageSlice;
import qg.qgent.common.storage.AvatarStorageService;
import qg.qgent.common.web.ApiException;
import qg.qgent.notification.service.NotificationService;
import qg.qgent.project.dto.*;
import qg.qgent.project.entity.ProjectEntity;
import qg.qgent.project.entity.ProjectMemberEntity;
import qg.qgent.project.event.ProjectCreatedEvent;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.mapper.ProjectMemberMapper;
import qg.qgent.project.mapper.view.ProjectMembershipView;
import qg.qgent.realtime.service.EventService;
import qg.qgent.team.entity.TeamEntity;
import qg.qgent.team.entity.TeamMemberEntity;
import qg.qgent.team.mapper.TeamMapper;
import qg.qgent.team.mapper.TeamMemberMapper;
import qg.qgent.user.dto.AvatarConfirmResponse;
import qg.qgent.workspace.service.WorkspaceService;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ProjectService {
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper memberMapper;
    private final TeamMapper teamMapper;
    private final TeamMemberMapper teamMemberMapper;
    private final ProjectAccessService access;
    private final ApplicationEventPublisher eventPublisher;
    private final NotificationService notificationService;
    private final EventService eventService;
    private final WorkspaceService workspaceService;
    private final AvatarStorageService avatarStorage;
    private final AgentService agentService;

    /**
     * 创建项目并初始化项目成员关系；团队所有者和管理员均可创建。
     */
    @Transactional
    public ProjectResponse create(Long actor, Long teamId, ProjectCreateRequest request) {
        TeamEntity team = requireTeamForUpdate(teamId);
        access.requireTeamManager(team, actor);
        ProjectEntity project = new ProjectEntity();
        project.setTeamId(teamId);
        project.setCreatedBy(actor);
        project.setName(request.getName().trim());
        project.setDescription(request.getDescription());
        project.setStatus("ACTIVE");
        projectMapper.insert(project);
        // 默认 Workspace 与项目同一事务登记；物理目录和沙箱仍按需建立。
        workspaceService.defaultWorkspace(project.getId());
        // 每个项目拥有自己的默认 Agent；默认群在同一事务里把它加为成员。
        agentService.createDefaultAgent(project.getId());

        insertMember(project.getId(), actor, "PROJECT_ADMIN");
        for (Long userId :
            new LinkedHashSet<>(
                request.getMemberIds() == null ? List.of() : request.getMemberIds())) {
            if (actor.equals(userId)) {
                continue;
            }
            requireTeamMember(teamId, userId);
            insertMember(project.getId(), userId, "PROJECT_MEMBER");
        }
        eventPublisher.publishEvent(
            new ProjectCreatedEvent(project.getId(), project.getName(), actor));
        // 项目创建事务提交后保存事件，供团队最近动态展示。
        eventService.publish(
            project.getId(),
            null,
            "project.created",
            project.getId().toString(),
            Map.of(
                "projectId",
                id(project.getId()),
                "name",
                project.getName(),
                "createdBy",
                id(actor)));
        return ProjectViews.response(
            project, "PROJECT_ADMIN", memberMapper.countMembers(project.getId()));
    }

    public PageSlice<ProjectResponse> list(Long actor, Long teamId, String cursor, Integer limit) {
        requireTeam(teamId);
        requireTeamMember(teamId, actor);
        boolean teamOwner = access.isCanonicalTeamOwner(teamId, actor);
        int size = KeysetPagination.pageSize(limit);
        String scope = "team-projects:" + teamId + ":" + actor;
        Long anchor = KeysetPagination.decodeCursor(cursor, scope);
        List<ProjectMembershipView> rows =
            projectMapper.selectAccessiblePage(teamId, actor, teamOwner, anchor, size + 1);
        return KeysetPagination.keysetPage(
            rows, size, scope, ProjectMembershipView::getId, ProjectViews::response);
    }

    public ProjectResponse get(Long actor, Long projectId) {
        ProjectEntity project = requireActiveProject(projectId);
        return ProjectViews.response(
            project,
            access.requireAccess(project, actor),
            memberMapper.countMembers(project.getId()));
    }

    @Transactional
    public ProjectResponse update(Long actor, Long projectId, ProjectUpdateRequest request) {
        ProjectEntity project = requireProjectForUpdate(projectId);
        access.requireProjectAdminAnyState(project, actor);
        requireActive(project);
        // PATCH 语义：name/description/avatarUrl 均可单独更新；全 null 时拒绝空更新
        if (request.getName() == null
            && request.getDescription() == null
            && request.getAvatarUrl() == null) {
            throw invalid("至少需要提供 name、description 或 avatarUrl");
        }
        if (request.getName() != null) {
            if (request.getName().isBlank()) {
                throw invalid("项目名称不能为空");
            }
            project.setName(request.getName().trim());
        }
        if (request.getDescription() != null) {
            project.setDescription(request.getDescription());
        }
        // 头像只能通过上传确认接口设置；空串表示清空
        if (request.getAvatarUrl() != null) {
            if (!request.getAvatarUrl().isBlank()) {
                throw invalid("头像只能通过头像上传接口设置");
            }
            String previousAvatar = project.getAvatarUrl();
            project.setAvatarUrl(null);
            avatarStorage.deleteReplacedAfterCommit("projects", projectId, previousAvatar, null);
        }
        projectMapper.updateById(project);
        return ProjectViews.response(
            project, "PROJECT_ADMIN", memberMapper.countMembers(project.getId()));
    }

    /**
     * 签发项目头像直传凭证；权限与修改项目资料一致。
     */
    public AvatarStorageService.AvatarCredential avatarCredential(
        Long actor, Long projectId, String mediaType, Long sizeBytes) {
        ProjectEntity project = requireProjectForUpdate(projectId);
        access.requireProjectAdminAnyState(project, actor);
        requireActive(project);
        return avatarStorage.createCredential("projects", projectId, mediaType, sizeBytes);
    }

    /**
     * 确认项目头像上传：直接写入头像 URL 并返回，前端无需再调修改接口。
     */
    @Transactional
    public AvatarConfirmResponse updateAvatar(Long actor, Long projectId, String objectKey) {
        ProjectEntity project = requireProjectForUpdate(projectId);
        access.requireProjectAdminAnyState(project, actor);
        requireActive(project);
        String publicUrl = avatarStorage.confirm("projects", projectId, objectKey);
        String previousAvatar = project.getAvatarUrl();
        project.setAvatarUrl(publicUrl);
        projectMapper.updateById(project);
        avatarStorage.deleteReplacedAfterCommit("projects", projectId, previousAvatar, objectKey);
        return new AvatarConfirmResponse(publicUrl);
    }

    @Transactional
    public ProjectResponse archive(Long actor, Long projectId) {
        return transition(actor, projectId, "ARCHIVED");
    }

    @Transactional
    public ProjectResponse restore(Long actor, Long projectId) {
        return transition(actor, projectId, "ACTIVE");
    }

    public PageSlice<ProjectMemberResponse> members(
        Long actor, Long projectId, String cursor, Integer limit) {
        ProjectEntity project = requireActiveProject(projectId);
        access.requireAccess(project, actor);
        int size = KeysetPagination.pageSize(limit);
        String scope = "project-members:" + projectId;
        Long anchor = KeysetPagination.decodeCursor(cursor, scope);
        List<ProjectMemberEntity> rows = memberMapper.selectMemberPage(projectId, anchor, size + 1);
        return KeysetPagination.keysetPage(
            rows,
            size,
            scope,
            ProjectMemberEntity::getUserId,
            member ->
                new ProjectMemberResponse(member.getUserId().toString(), member.getRole()));
    }

    @Transactional
    public ProjectMemberResponse addMember(
        Long actor, Long projectId, ProjectMemberAddRequest request) {
        ProjectEntity project = lockTeamThenProject(projectId);
        access.requireProjectAdminAnyState(project, actor);
        requireActive(project);
        requireTeamMember(project.getTeamId(), request.getUserId());
        if (memberMapper.selectByProjectAndUser(projectId, request.getUserId()) != null) {
            throw conflict("PROJECT_MEMBER_ALREADY_EXISTS", "用户已是项目成员");
        }
        insertMember(projectId, request.getUserId(), "PROJECT_MEMBER");
        // 通知被加入项目的成员
        notificationService.notify(
            request.getUserId(),
            projectId,
            null,
            "PROJECT_ADDED",
            "你被加入项目 " + project.getName(),
            null,
            projectId.toString());
        // 告知团队在线客户端有新项目成员。
        eventService.publishTeamEvent(
            project.getTeamId(),
            "project.member.added",
            id(projectId),
            Map.of("teamId", id(project.getTeamId()), "projectId", id(projectId)));
        return new ProjectMemberResponse(request.getUserId().toString(), "PROJECT_MEMBER");
    }

    private String id(Long value) {
        return value == null ? null : value.toString();
    }

    @Transactional
    public ProjectMemberResponse updateMember(
        Long actor, Long projectId, Long userId, ProjectMemberUpdateRequest request) {
        ProjectEntity project = lockTeamThenProject(projectId);
        access.requireProjectAdminAnyState(project, actor);
        requireActive(project);
        if (!List.of("PROJECT_ADMIN", "PROJECT_MEMBER").contains(request.getRole())) {
            throw invalid("项目角色仅支持 PROJECT_ADMIN 或 PROJECT_MEMBER");
        }
        requireProjectMember(projectId, userId);
        if (access.isCanonicalTeamOwner(project.getTeamId(), userId)) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "TEAM_OWNER_PROJECT_ROLE_INHERITED",
                "团队所有者通过团队身份拥有项目管理员权限，不能通过项目成员接口调整");
        }
        memberMapper.updateRole(projectId, userId, request.getRole());
        return new ProjectMemberResponse(userId.toString(), request.getRole());
    }

    @Transactional
    public ProjectMemberResponse removeMember(Long actor, Long projectId, Long userId) {
        ProjectEntity project = lockTeamThenProject(projectId);
        access.requireProjectAdminAnyState(project, actor);
        requireActive(project);
        ProjectMemberEntity target = requireProjectMember(projectId, userId);
        if (access.isCanonicalTeamOwner(project.getTeamId(), userId)) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "TEAM_OWNER_PROJECT_ROLE_INHERITED",
                "团队所有者通过团队身份拥有项目管理员权限，不能通过项目成员接口移除");
        }
        memberMapper.deleteByProjectAndUser(projectId, userId);
        return new ProjectMemberResponse(userId.toString(), target.getRole());
    }

    private ProjectResponse transition(Long actor, Long projectId, String targetStatus) {
        ProjectEntity project = requireProjectForUpdate(projectId);
        access.requireAdmin(project, actor);
        if (!List.of("ACTIVE", "ARCHIVED").contains(project.getStatus())) {
            throw conflict("INVALID_PROJECT_STATE", "项目状态不允许变更");
        }
        // 重复归档或恢复返回当前结果，不制造额外状态变化。
        if (!targetStatus.equals(project.getStatus())) {
            project.setStatus(targetStatus);
            projectMapper.updateById(project);
        }
        return ProjectViews.response(
            project, "PROJECT_ADMIN", memberMapper.countMembers(project.getId()));
    }

    private void insertMember(Long projectId, Long userId, String role) {
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setProjectId(projectId);
        member.setUserId(userId);
        member.setRole(role);
        memberMapper.insert(member);
    }

    private TeamMemberEntity requireTeamMember(Long teamId, Long userId) {
        TeamMemberEntity member = teamMemberMapper.selectByTeamAndUser(teamId, userId);
        if (member == null) {
            throw new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY, "TEAM_MEMBER_REQUIRED", "用户必须属于该团队");
        }
        return member;
    }

    private ProjectMemberEntity requireProjectMember(Long projectId, Long userId) {
        ProjectMemberEntity member = memberMapper.selectByProjectAndUser(projectId, userId);
        if (member == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_MEMBER_NOT_FOUND", "项目成员不存在");
        }
        return member;
    }

    private TeamEntity requireTeam(Long teamId) {
        TeamEntity team = teamMapper.selectById(teamId);
        if (team == null || !"ACTIVE".equals(team.getStatus())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND", "团队不存在或不可见");
        }
        return team;
    }

    private TeamEntity requireTeamForUpdate(Long teamId) {
        TeamEntity team = teamMapper.selectByIdForUpdate(teamId);
        if (team == null || !"ACTIVE".equals(team.getStatus())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND", "团队不存在或不可见");
        }
        return team;
    }

    private ProjectEntity requireActiveProject(Long projectId) {
        ProjectEntity project = projectMapper.selectById(projectId);
        if (project == null || !"ACTIVE".equals(project.getStatus())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在或不可见");
        }
        return project;
    }

    private ProjectEntity requireProjectForUpdate(Long projectId) {
        ProjectEntity project = projectMapper.selectByIdForUpdate(projectId);
        if (project == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在或不可见");
        }
        return project;
    }

    /**
     * 先锁团队再锁项目，与团队成员删除保持相同顺序。
     */
    private ProjectEntity lockTeamThenProject(Long projectId) {
        ProjectEntity observed = projectMapper.selectById(projectId);
        if (observed == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在或不可见");
        }
        TeamEntity team = requireTeamForUpdate(observed.getTeamId());
        ProjectEntity locked = requireProjectForUpdate(projectId);
        if (!team.getId().equals(locked.getTeamId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在或不可见");
        }
        return locked;
    }

    private void requireActive(ProjectEntity project) {
        if (!"ACTIVE".equals(project.getStatus())) {
            throw conflict("PROJECT_ARCHIVED", "归档项目不可执行该操作");
        }
    }

    private ApiException invalid(String message) {
        return new ApiException(
            HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PROJECT_OPERATION", message);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
