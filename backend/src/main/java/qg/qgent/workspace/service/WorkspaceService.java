package qg.qgent.workspace.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import qg.qgent.workspace.entity.WorkspaceEntity;
import qg.qgent.workspace.mapper.WorkspaceMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 管理工作区记录；实际目录及沙箱由 Sandbox 准备。
 */
@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private final WorkspaceMapper workspaceMapper;

    /**
     * 项目默认 Workspace：每个项目唯一，跨运行复用。
     * 并发首次调用时只有一个插入成功，其余读取已存在记录。
     */
    public WorkspaceEntity defaultWorkspace(Long projectId) {
        WorkspaceEntity existing =
            workspaceMapper.selectOne(
                Wrappers.<WorkspaceEntity>lambdaQuery()
                    .eq(WorkspaceEntity::getProjectId, projectId)
                    .eq(WorkspaceEntity::getIsDefault, true));
        if (existing != null) {
            return existing;
        }
        try {
            return insert(projectId, true);
        } catch (DuplicateKeyException exception) {
            return workspaceMapper.selectOne(
                Wrappers.<WorkspaceEntity>lambdaQuery()
                    .eq(WorkspaceEntity::getProjectId, projectId)
                    .eq(WorkspaceEntity::getIsDefault, true));
        }
    }

    /**
     * 用户明确要求隔离时创建的额外 Workspace。
     */
    public WorkspaceEntity create(Long projectId) {
        return insert(projectId, false);
    }

    public List<WorkspaceEntity> list(Long projectId) {
        return workspaceMapper.selectList(
            Wrappers.<WorkspaceEntity>lambdaQuery()
                .eq(WorkspaceEntity::getProjectId, projectId)
                .orderByDesc(WorkspaceEntity::getIsDefault)
                .orderByAsc(WorkspaceEntity::getId));
    }

    public WorkspaceEntity require(Long projectId, Long workspaceId) {
        WorkspaceEntity workspace = workspaceMapper.selectById(workspaceId);
        if (workspace == null
            || !projectId.equals(workspace.getProjectId())
            || "ARCHIVED".equals(workspace.getStatus()))
            throw new IllegalArgumentException("工作区不存在或不可用");
        return workspace;
    }

    /**
     * 沙箱与目录层使用的存储标识：<projectId>/workspaces/<default|workspaceId>。
     */
    public static String storageKey(WorkspaceEntity workspace) {
        return workspace.getProjectId() + "/workspaces/" + directoryName(workspace);
    }

    /**
     * Workspace 目录名：默认 Workspace 固定为 default，其余使用 Workspace ID。
     */
    public static String directoryName(WorkspaceEntity workspace) {
        return Boolean.TRUE.equals(workspace.getIsDefault())
            ? "default"
            : workspace.getId().toString();
    }

    private WorkspaceEntity insert(Long projectId, boolean isDefault) {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setProjectId(projectId);
        workspace.setIsDefault(isDefault);
        workspace.setStatus("PROVISIONING");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        workspace.setCreatedAt(now);
        workspace.setUpdatedAt(now);
        workspaceMapper.insert(workspace);
        return workspace;
    }
}
