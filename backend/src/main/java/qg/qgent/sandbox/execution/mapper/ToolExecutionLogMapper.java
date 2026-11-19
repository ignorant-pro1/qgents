package qg.qgent.sandbox.execution.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import qg.qgent.sandbox.execution.entity.ToolExecutionLogEntity;

/**
 * 工具执行日志的数据访问接口。
 */
@Mapper
public interface ToolExecutionLogMapper {
    @Insert(
        """
            INSERT INTO tool_execution_logs
                (execution_id, sequence_no, stream, content, created_at)
            VALUES
                (#{executionId}, #{sequenceNo}, #{stream}, #{content}, #{createdAt})
            """)
    int insert(ToolExecutionLogEntity entity);

    @Select(
        "SELECT COALESCE(MAX(sequence_no), 0) FROM tool_execution_logs WHERE execution_id = #{executionId}")
    long selectMaxSequence(Long executionId);
}
