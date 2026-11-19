package qg.qgent.sandbox.execution.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import qg.qgent.sandbox.execution.entity.ExecutionStatus;
import qg.qgent.sandbox.execution.entity.ToolExecutionEntity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工具执行记录的数据访问接口。
 */
@Mapper
public interface ToolExecutionMapper extends BaseMapper<ToolExecutionEntity> {
    @Update(
        """
            UPDATE tool_executions
            SET status = 'INTERRUPTED', failure_code = 'APPLICATION_RESTART_INTERRUPTED',
                failure_reason = 'Sandbox 重启，执行状态已中断', finished_at = #{finishedAt}
            WHERE owner_instance_id = #{instanceId} AND status IN ('QUEUED', 'RUNNING')
            """)
    int markInterrupted(
        @Param("instanceId") String instanceId, @Param("finishedAt") LocalDateTime finishedAt);

    @Update(
        """
            UPDATE tool_executions
            SET status = 'RUNNING', started_at = #{startedAt}
            WHERE id = #{id} AND owner_instance_id = #{instanceId} AND status = 'QUEUED'
            """)
    int markRunning(
        @Param("id") Long id,
        @Param("instanceId") String instanceId,
        @Param("startedAt") LocalDateTime startedAt);

    @Update(
        """
            UPDATE tool_executions
            SET status = 'CANCELLED', failure_code = 'EXECUTION_CANCELLED',
                failure_reason = '执行已取消', finished_at = #{finishedAt}
            WHERE id = #{id} AND owner_instance_id = #{instanceId} AND status IN ('QUEUED', 'RUNNING')
            """)
    int markCancelled(
        @Param("id") Long id,
        @Param("instanceId") String instanceId,
        @Param("finishedAt") LocalDateTime finishedAt);

    @Update(
        """
            UPDATE tool_executions
            SET status = #{status}, exit_code = #{exitCode}, result_json = #{resultJson},
                failure_code = #{failureCode}, failure_reason = #{failureReason}, finished_at = #{finishedAt}
            WHERE id = #{id} AND owner_instance_id = #{instanceId} AND status = 'RUNNING'
            """)
    int finishIfRunning(
        @Param("id") Long id,
        @Param("instanceId") String instanceId,
        @Param("status") ExecutionStatus status,
        @Param("exitCode") Integer exitCode,
        @Param("resultJson") String resultJson,
        @Param("failureCode") String failureCode,
        @Param("failureReason") String failureReason,
        @Param("finishedAt") LocalDateTime finishedAt);

    @Update(
        """
            UPDATE tool_executions
            SET status = 'FAILED', failure_code = #{failureCode}, failure_reason = #{failureReason},
                finished_at = #{finishedAt}
            WHERE id = #{id} AND owner_instance_id = #{instanceId} AND status = 'QUEUED'
            """)
    int rejectQueued(
        @Param("id") Long id,
        @Param("instanceId") String instanceId,
        @Param("failureCode") String failureCode,
        @Param("failureReason") String failureReason,
        @Param("finishedAt") LocalDateTime finishedAt);

    @Select(
        """
            SELECT id FROM tool_executions
            WHERE sandbox_id = #{sandboxId} AND owner_instance_id = #{instanceId}
              AND status IN ('QUEUED', 'RUNNING')
            """)
    List<Long> selectActiveIdsBySandbox(
        @Param("sandboxId") String sandboxId, @Param("instanceId") String instanceId);

    @Select(
        """
            SELECT id FROM tool_executions
            WHERE agent_run_id = #{agentRunId} AND owner_instance_id = #{instanceId}
              AND status IN ('QUEUED', 'RUNNING')
            """)
    List<Long> selectActiveIdsByAgentRun(
        @Param("agentRunId") Long agentRunId, @Param("instanceId") String instanceId);
}
