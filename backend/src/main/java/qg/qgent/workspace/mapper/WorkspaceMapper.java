package qg.qgent.workspace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import qg.qgent.workspace.entity.WorkspaceEntity;

/**
 * 工作区记录与写入租约的数据库操作。
 */
@Mapper
public interface WorkspaceMapper extends BaseMapper<WorkspaceEntity> {
    /**
     * 领取工作区写入租约；旧租约到期或所属运行结束后可接管。
     */
    @Update(
        "update workspaces set write_lease_agent_run_id=#{agentRunId}, write_lease_token=#{token}, "
            + "write_lease_expires_at=DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 30 MINUTE), updated_at=UTC_TIMESTAMP(6) "
            + "where id=#{workspaceId} and project_id=#{projectId} and (write_lease_expires_at is null "
            + "or write_lease_expires_at <= UTC_TIMESTAMP(6) or (write_lease_agent_run_id=#{agentRunId} "
            + "and write_lease_token=#{token}) or exists (select 1 from agent_runs owner_run "
            + "where owner_run.id=write_lease_agent_run_id and owner_run.project_id=#{projectId} "
            + "and owner_run.status in ('SUCCEEDED','FAILED','CANCELLED')))")
    int claimWriteLease(
        @Param("projectId") Long projectId,
        @Param("workspaceId") Long workspaceId,
        @Param("agentRunId") Long agentRunId,
        @Param("token") String token);

    /**
     * 仅当前持有者可续租；返回 0 表示租约已被接管、过期或 Workspace 归属不匹配。
     */
    @Update(
        "update workspaces set write_lease_expires_at=DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 30 MINUTE), "
            + "updated_at=UTC_TIMESTAMP(6) where id=#{workspaceId} and project_id=#{projectId} "
            + "and write_lease_agent_run_id=#{agentRunId} and write_lease_token=#{token} "
            + "and write_lease_expires_at > UTC_TIMESTAMP(6)")
    int renewWriteLease(
        @Param("projectId") Long projectId,
        @Param("workspaceId") Long workspaceId,
        @Param("agentRunId") Long agentRunId,
        @Param("token") String token);

    /**
     * 仅当前令牌可释放租约，防止旧进程迟到的 finally 清理掉新持有者。
     */
    @Update(
        "update workspaces set write_lease_agent_run_id=null, write_lease_token=null, write_lease_expires_at=null, "
            + "updated_at=UTC_TIMESTAMP(6) where id=#{workspaceId} and project_id=#{projectId} "
            + "and write_lease_agent_run_id=#{agentRunId} and write_lease_token=#{token}")
    int releaseWriteLease(
        @Param("projectId") Long projectId,
        @Param("workspaceId") Long workspaceId,
        @Param("agentRunId") Long agentRunId,
        @Param("token") String token);
}
