package qg.qgent.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import qg.qgent.user.entity.UserEntity;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {

    @Update("UPDATE users SET last_team_id = #{teamId} WHERE id = #{userId}")
    int updateLastTeam(@Param("userId") Long userId, @Param("teamId") Long teamId);

    /**
     * 锁定用户行，用作该用户通知序号分配的跨实例串行化锚点。
     */
    @Select(
        "SELECT id, email, display_name, avatar_url, last_team_id, password_hash, "
            + "status, created_at, updated_at "
            + "FROM users WHERE id = #{userId} FOR UPDATE")
    @Results({
        @Result(column = "id", property = "id"),
        @Result(column = "password_hash", property = "passwordHash"),
        @Result(column = "display_name", property = "displayName"),
        @Result(column = "avatar_url", property = "avatarUrl"),
        @Result(column = "last_team_id", property = "lastTeamId"),
        @Result(column = "status", property = "status"),
        @Result(column = "created_at", property = "createdAt")
    })
    UserEntity selectByIdForUpdate(@Param("userId") Long userId);
}
