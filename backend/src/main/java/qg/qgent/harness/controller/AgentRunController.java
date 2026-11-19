package qg.qgent.harness.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import qg.qgent.common.web.ApiException;
import qg.qgent.common.web.ApiResponse;
import qg.qgent.common.web.RequestIdFilter;
import qg.qgent.harness.dto.AgentRunSnapshot;
import qg.qgent.harness.runtime.AgentStreamService;

import java.util.List;

/**
 * 群内 Agent 运行的实时增量与刷新快照。
 */
@RestController
@RequiredArgsConstructor
public class AgentRunController {
    private static final int MAX_RECENT_RUNS = 50;

    private final AgentStreamService streams;

    /**
     * 订阅群级 Agent 运行事件；建连即收到活动运行快照，断线后重连以快照恢复。
     */
    @GetMapping(
        value = "/api/v1/projects/{projectId}/groups/{groupId}/agent-events",
        produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long projectId,
        @PathVariable Long groupId) {
        return streams.openGroupStream(projectId, groupId, userId);
    }

    /**
     * 群内最近的运行（含子运行），用于刷新和降级轮询。
     */
    @GetMapping("/api/v1/projects/{projectId}/agent-runs")
    public ApiResponse<List<AgentRunSnapshot>> recent(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long projectId,
        @RequestParam Long groupId,
        @RequestParam(defaultValue = "20") int limit,
        HttpServletRequest request) {
        if (limit < 1 || limit > MAX_RECENT_RUNS) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "INVALID_LIMIT",
                "limit 必须为 1～" + MAX_RECENT_RUNS);
        }
        return ApiResponse.ok(
            streams.recentRuns(projectId, groupId, userId, limit),
            (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
