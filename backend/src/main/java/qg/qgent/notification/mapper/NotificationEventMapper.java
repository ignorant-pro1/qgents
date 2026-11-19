package qg.qgent.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import qg.qgent.notification.entity.NotificationEventEntity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通知级 SSE 事件数据访问（用户维度游标）。
 */
@Mapper
public interface NotificationEventMapper extends BaseMapper<NotificationEventEntity> {

    /**
     * 用户当前最大事件序号；无事件时返回 0。
     */
    @Select(
        "select coalesce(max(sequence_no), 0) from notification_events where recipient_user_id=#{userId}")
    Long maxSequence(@Param("userId") Long userId);

    /**
     * 用户最小事件序号；无事件时返回 null。
     */
    @Select("select min(sequence_no) from notification_events where recipient_user_id=#{userId}")
    Long minSequence(@Param("userId") Long userId);

    /**
     * 拉取用户某游标之后的增量事件。
     */
    @Select(
        "select * from notification_events where recipient_user_id=#{userId} and sequence_no > #{after} "
            + "order by sequence_no asc limit #{limit}")
    List<NotificationEventEntity> listAfter(
        @Param("userId") Long userId, @Param("after") long after, @Param("limit") int limit);

    /**
     * 删除早于 cutoff 的过期事件，与项目事件同保留期。
     */
    @Delete("delete from notification_events where created_at < #{cutoff}")
    int deleteExpired(@Param("cutoff") LocalDateTime cutoff);
}
