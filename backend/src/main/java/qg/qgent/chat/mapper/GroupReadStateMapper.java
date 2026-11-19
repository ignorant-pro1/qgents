package qg.qgent.chat.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 群成员已读游标数据访问（表 group_read_state）。
 */
@Mapper
public interface GroupReadStateMapper {

    /**
     * 推进某用户在某群的已读游标，只前进不后退：目标游标小于当前值时忽略。
     * <p>
     * 采用 MySQL {@code INSERT ... ON DUPLICATE KEY UPDATE} 并用 {@code GREATEST} 保证单调不减，
     * 天然满足幂等（重复调用无副作用）。调用方需已校验项目/群归属。
     *
     * @param userId  用户 ID
     * @param groupId 项目群聊 ID
     * @param seq     目标已读游标（群内消息序号）
     */
    @Insert(
        "INSERT INTO group_read_state (user_id, group_id, last_read_sequence_no) "
            + "VALUES (#{userId}, #{groupId}, #{seq}) "
            + "ON DUPLICATE KEY UPDATE last_read_sequence_no = "
            + "GREATEST(COALESCE(last_read_sequence_no, 0), VALUES(last_read_sequence_no))")
    void upsertSequence(
        @Param("userId") Long userId, @Param("groupId") Long groupId, @Param("seq") Long seq);
}
