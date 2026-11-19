package qg.qgent.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import qg.qgent.chat.mapper.view.GroupMemberRow;
import qg.qgent.project.entity.ProjectMemberEntity;

import java.util.List;

@Mapper
public interface ProjectMemberMapper extends BaseMapper<ProjectMemberEntity> {
    @Delete(
        "DELETE pm FROM project_members pm INNER JOIN projects p ON p.id = pm.project_id WHERE p.team_id = #{teamId} AND pm.user_id = #{userId}")
    int deleteByTeamAndUser(@Param("teamId") Long teamId, @Param("userId") Long userId);

    @Select(
        "SELECT project_id, user_id, role FROM project_members WHERE project_id = #{projectId} AND user_id = #{userId}")
    @Results({
        @Result(column = "project_id", property = "projectId"),
        @Result(column = "user_id", property = "userId"),
        @Result(column = "role", property = "role")
    })
    ProjectMemberEntity selectByProjectAndUser(
        @Param("projectId") Long projectId, @Param("userId") Long userId);

    @Select({
        "<script>",
        "SELECT project_id, user_id, role FROM project_members WHERE project_id = #{projectId}",
        "<if test='anchor != null'>AND user_id &gt; #{anchor}</if>",
        "ORDER BY user_id LIMIT #{limit}",
        "</script>"
    })
    @Results({
        @Result(column = "project_id", property = "projectId"),
        @Result(column = "user_id", property = "userId")
    })
    List<ProjectMemberEntity> selectMemberPage(
        @Param("projectId") Long projectId,
        @Param("anchor") Long anchor,
        @Param("limit") int limit);

    @Update(
        "UPDATE project_members SET role = #{role} WHERE project_id = #{projectId} AND user_id = #{userId}")
    int updateRole(
        @Param("projectId") Long projectId,
        @Param("userId") Long userId,
        @Param("role") String role);

    @Delete("DELETE FROM project_members WHERE project_id = #{projectId} AND user_id = #{userId}")
    int deleteByProjectAndUser(@Param("projectId") Long projectId, @Param("userId") Long userId);

    /**
     * 查询项目成员列表（含昵称、头像、邮箱），按加入时间排序（默认群聊成员来自项目成员）。
     */
    @Select(
        "SELECT u.id AS user_id, u.display_name, u.avatar_url, u.email FROM project_members pm"
            + " JOIN users u ON u.id = pm.user_id WHERE pm.project_id = #{projectId} ORDER BY pm.joined_at")
    @Results({
        @Result(column = "user_id", property = "userId"),
        @Result(column = "display_name", property = "displayName"),
        @Result(column = "avatar_url", property = "avatarUrl"),
        @Result(column = "email", property = "email")
    })
    List<GroupMemberRow> selectMembers(@Param("projectId") Long projectId);

    /**
     * 统计项目成员数（用作群详情 memberCount）。
     */
    @Select("SELECT COUNT(*) FROM project_members WHERE project_id = #{projectId}")
    Long countMembers(@Param("projectId") Long projectId);
}
