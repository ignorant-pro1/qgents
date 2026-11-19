package qg.qgent.team.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import qg.qgent.team.entity.TeamEntity;
import qg.qgent.team.mapper.view.TeamMembershipView;

import java.util.List;

@Mapper
public interface TeamMapper extends BaseMapper<TeamEntity> {
    @Select(
        "SELECT id, owner_user_id, name, description, avatar_url, status, created_at FROM teams WHERE id = #{teamId} FOR UPDATE")
    @Results({
        @Result(column = "id", property = "id"),
        @Result(column = "owner_user_id", property = "ownerUserId"),
        @Result(column = "name", property = "name"),
        @Result(column = "description", property = "description"),
        @Result(column = "avatar_url", property = "avatarUrl"),
        @Result(column = "status", property = "status"),
        @Result(column = "created_at", property = "createdAt")
    })
    TeamEntity selectByIdForUpdate(Long teamId);

    @Select({
        "<script>",
        "SELECT t.id, t.owner_user_id, t.name, t.description, t.avatar_url, t.created_at, tm.role,",
        "(SELECT COUNT(*) FROM team_members tc WHERE tc.team_id = t.id) AS member_count",
        "FROM team_members tm INNER JOIN teams t ON t.id = tm.team_id",
        "WHERE tm.user_id = #{userId}",
        "<if test='anchor != null'>AND t.id &gt; #{anchor}</if>",
        "ORDER BY t.id LIMIT #{limit}",
        "</script>"
    })
    @Results(
        id = "membership",
        value = {
            @Result(column = "id", property = "id"),
            @Result(column = "owner_user_id", property = "ownerUserId"),
            @Result(column = "name", property = "name"),
            @Result(column = "description", property = "description"),
            @Result(column = "avatar_url", property = "avatarUrl"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "role", property = "role"),
            @Result(column = "member_count", property = "memberCount")
        })
    List<TeamMembershipView> selectMembershipPage(
        @Param("userId") Long userId, @Param("anchor") Long anchor, @Param("limit") int limit);
}
