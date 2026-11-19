package qg.qgent.workspace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目中的持久化工作目录；目录名由项目 ID 与是否默认决定，见 WorkspacePaths。
 */
@Data
@TableName("workspaces")
public class WorkspaceEntity {
    // 工作区 ID。
    @TableId(type = IdType.AUTO)
    private Long id;

    // 所属项目。
    private Long projectId;
    // 是否为项目默认 Workspace；每个项目恰好一个。
    private Boolean isDefault;
    // 准备状态：创建时为 PROVISIONING，Sandbox 准备完成后为 READY。
    private String status;
    // 当前持有写入租约的根 AgentRun，不代表工作区的归属。
    private Long writeLeaseAgentRunId;
    // 续租和释放时匹配的随机令牌。
    private String writeLeaseToken;
    // 租约过期时间（UTC）；每次 Sandbox 工具调用前续租。
    private LocalDateTime writeLeaseExpiresAt;
    // 创建时间（UTC）。
    private LocalDateTime createdAt;
    // 更新时间（UTC）。
    private LocalDateTime updatedAt;
}
