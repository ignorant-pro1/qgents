package qg.qgent.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import qg.qgent.notification.entity.NotificationEntity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通知查询与已读更新。
 */
@Mapper
public interface NotificationMapper extends BaseMapper<NotificationEntity> {

    /**
     * 查询指定用户的通知列表，按产生时间倒序，不分页。
     */
    @Select(
        "SELECT * FROM notifications WHERE recipient_user_id = #{recipientUserId} ORDER BY created_at DESC")
    List<NotificationEntity> listByRecipient(@Param("recipientUserId") Long recipientUserId);

    /**
     * 将属于指定用户的一条通知标记为已读；不属于该用户时返回 0（幂等）。
     */
    @Update(
        "UPDATE notifications SET is_read = 1, read_at = #{readTime} "
            + "WHERE id = #{id} AND recipient_user_id = #{userId}")
    int markRead(
        @Param("id") Long id,
        @Param("userId") Long userId,
        @Param("readTime") LocalDateTime readTime);

    /**
     * 将指定用户的全部未读通知标记为已读（幂等）。
     */
    @Update(
        "UPDATE notifications SET is_read = 1, read_at = #{readTime} "
            + "WHERE recipient_user_id = #{userId} AND is_read = 0")
    int markAllRead(@Param("userId") Long userId, @Param("readTime") LocalDateTime readTime);
}
