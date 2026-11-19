package qg.qgent.user.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 用户 × 群置顶偏好数据访问（表 user_group_preference）。
 */
@Mapper
public interface UserGroupPreferenceMapper {

    /**
     * 设置 / 取消某用户对某群的置顶状态。
     */
    @Insert(
        "INSERT INTO user_group_preference (user_id, group_id, pinned) "
            + "VALUES (#{userId}, #{groupId}, #{pinned}) "
            + "ON DUPLICATE KEY UPDATE pinned = VALUES(pinned)")
    void upsertPin(
        @Param("userId") Long userId,
        @Param("groupId") Long groupId,
        @Param("pinned") boolean pinned);

    /**
     * 批量取某用户在一组群中的置顶群 ID（仅 pinned = true 者），一次 IN 查询避免 N+1。
     */
    @Select({
        "<script>",
        "SELECT group_id FROM user_group_preference WHERE user_id = #{userId} AND pinned = 1 ",
        "AND group_id IN "
            + "(<foreach collection='groupIds' item='gid' separator=','>#{gid}</foreach>)",
        "</script>"
    })
    @Results({@Result(column = "group_id", property = "groupId")})
    List<Long> selectPinnedGroupIds(
        @Param("userId") Long userId, @Param("groupIds") List<Long> groupIds);
}
