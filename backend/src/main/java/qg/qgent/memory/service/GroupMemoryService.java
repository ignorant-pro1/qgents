package qg.qgent.memory.service;

import org.springframework.stereotype.Service;
import qg.qgent.chat.service.GroupService;
import qg.qgent.memory.dto.GroupMemoryResponse;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.project.storage.ProjectDirectoryService;

import java.nio.file.Path;

/**
 * 群聊记忆：每个群在项目目录 groups/&lt;groupId&gt;/memory.md 保存独立正文，只由后台提炼任务写入。
 */
@Service
public class GroupMemoryService {

    private final ProjectAccessService access;
    private final GroupService groups;
    private final ProjectDirectoryService directories;
    private final MemoryDocument document;

    public GroupMemoryService(
        ProjectAccessService access,
        GroupService groups,
        ProjectDirectoryService directories,
        MemoryDocument document) {
        this.access = access;
        this.groups = groups;
        this.directories = directories;
        this.document = document;
    }

    /**
     * 管理员只读查看：项目管理员身份和来源群成员身份必须同时满足。
     */
    public GroupMemoryResponse get(Long actor, Long projectId, Long groupId) {
        access.requireProjectAdmin(projectId, actor);
        groups.requireGroupMember(projectId, groupId, actor);
        return read(projectId, groupId);
    }

    /**
     * 读取群记忆；尚未提炼时返回空正文。
     */
    public GroupMemoryResponse read(Long projectId, Long groupId) {
        Path directory = directories.groupRoot(projectId, groupId);
        return new GroupMemoryResponse(
            id(projectId),
            id(groupId),
            document.read(directory),
            document.updatedAt(directory));
    }

    /**
     * 供后台提炼写入整篇群记忆；超出上下文预算时拒绝写入。
     */
    public void write(Long projectId, Long groupId, String content) {
        document.write(directories.groupRoot(projectId, groupId), content);
    }

    private String id(Long value) {
        return value == null ? null : value.toString();
    }
}
