package qg.qgent.harness.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import qg.qgent.harness.entity.AgentRunToolEntity;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Mapper
public interface AgentRunToolMapper extends BaseMapper<AgentRunToolEntity> {

    /**
     * 工具结束时结算状态与结果；开始行已由 {@code insert} 写入。
     */
    @Update(
        "UPDATE agent_run_tools SET state = #{state}, output = #{output}, "
            + "finished_at = #{finishedAt} "
            + "WHERE run_id = #{runId} AND tool_call_id = #{toolCallId}")
    void finish(
        @Param("runId") Long runId,
        @Param("toolCallId") String toolCallId,
        @Param("state") String state,
        @Param("output") String output,
        @Param("finishedAt") LocalDateTime finishedAt);

    /**
     * 一次取回多个运行的工具明细，按写入顺序即工具开始顺序返回。
     */
    @Select({
        "<script>",
        "SELECT * FROM agent_run_tools WHERE run_id IN",
        "<foreach collection='runIds' item='runId' open='(' separator=',' close=')'>",
        "#{runId}</foreach>",
        "ORDER BY id",
        "</script>"
    })
    List<AgentRunToolEntity> byRunIds(@Param("runIds") Collection<Long> runIds);
}
