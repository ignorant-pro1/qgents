package qg.qgent.team.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import qg.qgent.team.entity.TeamEventEntity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 团队级 SSE 事件数据访问（团队维度游标）。
 */
@Mapper
public interface TeamEventMapper extends BaseMapper<TeamEventEntity> {

    /**
     * 团队当前最大事件序号；无事件时返回 0。
     */
    @Select("select coalesce(max(sequence_no), 0) from team_events where team_id=#{teamId}")
    Long maxSequence(@Param("teamId") Long teamId);

    /**
     * 团队最小事件序号；无事件时返回 null。
     */
    @Select("select min(sequence_no) from team_events where team_id=#{teamId}")
    Long minSequence(@Param("teamId") Long teamId);

    /**
     * 拉取团队某游标之后的增量事件。
     */
    @Select(
        "select * from team_events where team_id=#{teamId} and sequence_no > #{after} "
            + "order by sequence_no asc limit #{limit}")
    List<TeamEventEntity> listAfter(
        @Param("teamId") Long teamId, @Param("after") long after, @Param("limit") int limit);

    /**
     * 删除早于 cutoff 的过期事件，与项目事件同保留期。
     */
    @Delete("delete from team_events where created_at < #{cutoff}")
    int deleteExpired(@Param("cutoff") LocalDateTime cutoff);
}
