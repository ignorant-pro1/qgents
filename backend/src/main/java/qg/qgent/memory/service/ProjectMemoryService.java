package qg.qgent.memory.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.memory.dto.ProjectMemoryResponse;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.project.storage.ProjectDirectoryService;
import qg.qgent.realtime.service.EventService;

import java.nio.file.Path;
import java.util.Map;

/**
 * 项目长期记忆：项目目录下的 memory.md 是正文唯一来源，整篇替换或按片段替换。
 * <p>
 * 只有项目管理员在对话中确认后才写入，其余成员和子 Agent 只读。
 */
@Service
public class ProjectMemoryService {

    private final ProjectAccessService access;
    private final ProjectMapper projects;
    private final ProjectDirectoryService directories;
    private final EventService events;
    private final MemoryDocument document;

    public ProjectMemoryService(
        ProjectAccessService access,
        ProjectMapper projects,
        ProjectDirectoryService directories,
        EventService events,
        MemoryDocument document) {
        this.access = access;
        this.projects = projects;
        this.directories = directories;
        this.events = events;
        this.document = document;
    }

    /**
     * 读取项目记忆；项目尚未写入时返回空正文。
     */
    public ProjectMemoryResponse get(Long actor, Long projectId) {
        access.requireProjectMember(projectId, actor);
        return read(projectId);
    }

    /**
     * 管理员确认后整篇替换 memory.md。
     * <p>
     * 内容与当前相同时直接返回，使写入重试可以幂等完成。
     */
    @Transactional
    public ProjectMemoryResponse update(Long actor, Long projectId, String content) {
        beginWrite(actor, projectId);
        String text = content.trim();
        ProjectMemoryResponse current = read(projectId);
        if (current.getContent().equals(text)) {
            return current;
        }
        document.write(directories.projectRoot(projectId), text);
        return publishUpdated(projectId);
    }

    /**
     * 管理员确认后按片段替换 memory.md；原文找不到或不唯一时原文件不动。
     */
    @Transactional
    public ProjectMemoryResponse edit(
        Long actor, Long projectId, String anchor, String replacement) {
        beginWrite(actor, projectId);
        document.edit(directories.projectRoot(projectId), anchor, replacement);
        return publishUpdated(projectId);
    }

    /**
     * 写入前的校验与行锁：同项目的读改写跨实例串行，并发的两次替换才不会互相吃掉对方的修改。
     */
    private void beginWrite(Long actor, Long projectId) {
        access.requireProjectAdmin(projectId, actor);
        projects.selectByIdForUpdate(projectId);
    }

    /**
     * 读回正文并广播更新。
     */
    private ProjectMemoryResponse publishUpdated(Long projectId) {
        ProjectMemoryResponse saved = read(projectId);
        events.publish(
            projectId,
            null,
            "memory.updated",
            projectId.toString(),
            Map.of(
                "projectId",
                projectId.toString(),
                "updatedAt",
                saved.getUpdatedAt().toString()));
        return saved;
    }

    /**
     * 读取正文与最后修改时间；不做权限检查，只给已通过成员校验的调用方和后台发现任务使用。
     */
    ProjectMemoryResponse read(Long projectId) {
        Path directory = directories.projectRoot(projectId);
        return new ProjectMemoryResponse(
            projectId.toString(), document.read(directory), document.updatedAt(directory));
    }
}
