package qg.qgent.agent.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import qg.qgent.agent.dto.AgentApplicationRequest;
import qg.qgent.agent.dto.AgentResponse;
import qg.qgent.agent.service.AgentApplicationService;
import qg.qgent.common.web.ApiResponse;
import qg.qgent.common.web.RequestIdFilter;

import java.util.List;

/**
 * 项目内 Agent 接口：查询已启用 Agent 供群设置邀请，以及成员提交自定义 Agent 申请。
 * 申请由项目管理员在群聊里审批，本接口不提供审批入口。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AgentController {

    private final AgentApplicationService applications;

    /**
     * 获取项目内已启用的 Agent
     */
    @GetMapping("/projects/{projectId}/agents")
    public ApiResponse<List<AgentResponse>> list(
        @AuthenticationPrincipal Long actor,
        @PathVariable Long projectId,
        HttpServletRequest request) {
        return ok(
            applications
                .enabledAgents(actor, projectId)
                .stream()
                .map(AgentResponse::of)
                .toList(),
            request);
    }

    /**
     * 提交自定义 Agent 申请
     */
    @PostMapping("/projects/{projectId}/agents/applications")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AgentResponse> apply(
        @AuthenticationPrincipal Long actor,
        @PathVariable Long projectId,
        @RequestBody AgentApplicationRequest body,
        HttpServletRequest request) {
        return ok(AgentResponse.of(applications.apply(actor, projectId, body)), request);
    }

    private <T> ApiResponse<T> ok(T value, HttpServletRequest request) {
        return ApiResponse.ok(value, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
