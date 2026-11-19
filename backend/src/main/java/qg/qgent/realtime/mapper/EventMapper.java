package qg.qgent.realtime.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import org.apache.ibatis.annotations.*;
import qg.qgent.realtime.entity.EventEntity;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface EventMapper extends BaseMapper<EventEntity> {

    /**
     * 获取项目内当前最大事件序号；无事件时返回 0。
     */
    @Select("SELECT COALESCE(MAX(sequence_no), 0) FROM events WHERE project_id = #{projectId}")
    long maxSequence(@Param("projectId") Long projectId);

    /**
     * 调用方先锁项目，再通过锁定读计算最新事件序号，避免读取旧快照。
     */
    @Select(
        "SELECT COALESCE(MAX(sequence_no), 0) + 1 FROM events WHERE project_id = #{projectId} FOR UPDATE")
    long nextSequence(@Param("projectId") Long projectId);

    /**
     * 获取项目内最小事件序号；无事件时返回 null，用于校验 SSE 续传游标是否仍在保留窗口内。
     */
    @Select("SELECT MIN(sequence_no) FROM events WHERE project_id = #{projectId}")
    Long minSequence(@Param("projectId") Long projectId);

    /**
     * 拉取项目内序号大于游标的事件，按序号升序，用于 SSE 增量推送。
     */
    @Select(
        "SELECT id, project_id, group_id, sequence_no, event_type, resource_id, payload, created_at "
            + "FROM events WHERE project_id = #{projectId} AND sequence_no > #{after} "
            + "ORDER BY sequence_no ASC LIMIT #{limit}")
    @Results({
        @Result(column = "id", property = "id"),
        @Result(column = "project_id", property = "projectId"),
        @Result(column = "group_id", property = "groupId"),
        @Result(column = "sequence_no", property = "sequenceNo"),
        @Result(column = "event_type", property = "eventType"),
        @Result(column = "resource_id", property = "resourceId"),
        @Result(column = "payload", property = "payload", typeHandler = JacksonTypeHandler.class),
        @Result(column = "created_at", property = "createdAt")
    })
    List<EventEntity> listAfter(
        @Param("projectId") Long projectId,
        @Param("after") long after,
        @Param("limit") int limit);

    /**
     * 删除全部早于 cutoff 的过期事件（每日定时清理）。
     */
    @Delete("DELETE FROM events WHERE created_at < #{cutoff}")
    int deleteExpired(@Param("cutoff") LocalDateTime cutoff);
}
