package qg.qgent.memory.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import qg.qgent.common.web.ApiResponse;
import qg.qgent.common.web.RequestIdFilter;
import qg.qgent.memory.dto.GroupMemoryResponse;
import qg.qgent.memory.dto.ProjectMemoryResponse;
import qg.qgent.memory.service.GroupMemoryService;
import qg.qgent.memory.service.ProjectMemoryService;

/**
 * 记忆接口：项目 memory.md 与每个群的群记忆都存放在项目目录；项目记忆只在管理员确认后由 Agent 写入。
 */
@RestController
@RequestMapping("/api/v1")
public class MemoryController {

    private final ProjectMemoryService memories;
    private final GroupMemoryService groupMemories;

    public MemoryController(ProjectMemoryService memories, GroupMemoryService groupMemories) {
        this.memories = memories;
        this.groupMemories = groupMemories;
    }

    @Operation(summary = "读取项目长期记忆", description = "返回 memory.md 全文和最后修改时间；项目成员均可读取。")
    @GetMapping("/projects/{projectId}/memory")
    public ApiResponse<ProjectMemoryResponse> get(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long projectId,
        HttpServletRequest request) {
        return ok(memories.get(userId, projectId), request);
    }

    @Operation(summary = "读取群聊记忆", description = "仅项目管理员且属于来源群时可读；群记忆只由后台提炼更新，成员通过对话纠错。")
    @GetMapping("/projects/{projectId}/groups/{groupId}/memory")
    public ApiResponse<GroupMemoryResponse> getGroup(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long projectId,
        @PathVariable Long groupId,
        HttpServletRequest request) {
        return ok(groupMemories.get(userId, projectId, groupId), request);
    }

    private <T> ApiResponse<T> ok(T data, HttpServletRequest request) {
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
