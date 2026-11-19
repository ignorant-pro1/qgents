package qg.qgent.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import qg.qgent.project.entity.ProjectEntity;
import qg.qgent.project.mapper.view.ProjectMembershipView;

import java.util.List;

@Mapper
public interface ProjectMapper extends BaseMapper<ProjectEntity> {
    @Select(
        "SELECT id, team_id, created_by, name, description, avatar_url, status FROM projects WHERE id = #{projectId} FOR UPDATE")
    @Results(
        id = "project",
        value = {
            @Result(column = "id", property = "id"),
            @Result(column = "team_id", property = "teamId"),
            @Result(column = "created_by", property = "createdBy"),
            @Result(column = "avatar_url", property = "avatarUrl")
        })
    ProjectEntity selectByIdForUpdate(@Param("projectId") Long projectId);

    @Select({
        "<script>",
        "SELECT p.id, p.team_id, p.name, p.description, p.avatar_url, p.status,",
        "(SELECT COUNT(*) FROM project_members mc WHERE mc.project_id = p.id) AS member_count,",
        "CASE WHEN #{teamOwner} THEN 'PROJECT_ADMIN' ELSE pm.role END AS role",
        "FROM projects p LEFT JOIN project_members pm ON pm.project_id = p.id AND pm.user_id = #{userId}",
        "WHERE p.team_id = #{teamId}",
        "<if test='teamOwner == false'>AND pm.user_id IS NOT NULL</if>",
        "<if test='anchor != null'>AND p.id &gt; #{anchor}</if>",
        "ORDER BY p.id LIMIT #{limit}",
        "</script>"
    })
    @Results(
        id = "membership",
        value = {
            @Result(column = "id", property = "id"),
            @Result(column = "team_id", property = "teamId"),
            @Result(column = "avatar_url", property = "avatarUrl"),
            @Result(column = "member_count", property = "memberCount")
        })
    List<ProjectMembershipView> selectAccessiblePage(
        @Param("teamId") Long teamId,
        @Param("userId") Long userId,
        @Param("teamOwner") boolean teamOwner,
        @Param("anchor") Long anchor,
        @Param("limit") int limit);

    @Select(
        "SELECT DISTINCT p.id, p.team_id, p.name, p.description, p.avatar_url, p.status, "
            + "CASE WHEN t.owner_user_id = #{userId} AND tom.role = 'TEAM_OWNER' "
            + "THEN 'PROJECT_ADMIN' ELSE pm.role END AS role "
            + "FROM projects p INNER JOIN teams t ON t.id = p.team_id "
            + "LEFT JOIN team_members tom ON tom.team_id = t.id AND tom.user_id = #{userId} "
            + "LEFT JOIN project_members pm ON pm.project_id = p.id AND pm.user_id = #{userId} "
            + "WHERE p.status = 'ACTIVE' AND t.status = 'ACTIVE' "
            + "AND ((t.owner_user_id = #{userId} AND tom.role = 'TEAM_OWNER') "
            + "OR pm.user_id IS NOT NULL) ORDER BY p.id")
    @ResultMap("membership")
    List<ProjectMembershipView> selectAccessibleByUser(@Param("userId") Long userId);
}
