package qg.qgent.chat.mapper;

import org.apache.ibatis.annotations.*;
import qg.qgent.chat.mapper.view.GroupMemberRow;

import java.util.List;

/**
 * 新建群的用户成员；默认群仍直接使用项目成员。
 */
@Mapper
public interface GroupUserMapper {
    @Insert("INSERT IGNORE INTO group_users(group_id, user_id) VALUES(#{groupId}, #{userId})")
    int insertUser(@Param("groupId") Long groupId, @Param("userId") Long userId);

    @Select(
        "SELECT u.id AS user_id, u.display_name, u.avatar_url FROM group_users gu "
            + "JOIN users u ON u.id = gu.user_id WHERE gu.group_id = #{groupId} ORDER BY u.display_name")
    @Results({@Result(column = "user_id", property = "userId")})
    List<GroupMemberRow> selectMembers(Long groupId);

    @Select("SELECT user_id FROM group_users WHERE group_id = #{groupId}")
    List<Long> selectUserIds(Long groupId);
}
