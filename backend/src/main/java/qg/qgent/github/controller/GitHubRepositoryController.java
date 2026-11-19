package qg.qgent.github.controller;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;
import qg.qgent.common.web.ApiResponse;
import qg.qgent.common.web.RequestIdFilter;
import qg.qgent.github.service.GitHubRepositoryService;

/**
 * 团队 App 安装管理与项目仓库导入；导入不会创建执行环境。
 */
@RestController
@RequestMapping("/api/v1")
public class GitHubRepositoryController {
    private final GitHubRepositoryService service;
    private final String frontendWeb;
    private final String frontendMobile;

    public GitHubRepositoryController(
        GitHubRepositoryService service,
        @Value("${app.frontend-url-web}") String frontendWeb,
        @Value("${app.frontend-url-mobile}") String frontendMobile) {
        this.service = service;
        this.frontendWeb = frontendWeb;
        this.frontendMobile = frontendMobile;
    }

    @PostMapping("/teams/{teamId}/integrations/github/installations")
    public ApiResponse<?> install(
        @AuthenticationPrincipal Long actor,
        @PathVariable Long teamId,
        @RequestParam(defaultValue = "WEB") String client,
        HttpServletRequest request) {
        return ok(service.createInstallationUrl(actor, teamId, client), request);
    }

    @GetMapping("/teams/{teamId}/integrations/github/installations")
    public ApiResponse<?> installations(
        @AuthenticationPrincipal Long actor,
        @PathVariable Long teamId,
        HttpServletRequest request) {
        return ok(service.listInstallations(actor, teamId), request);
    }

    @PostMapping("/teams/{teamId}/integrations/github/installations/{installationId}/sync")
    public ApiResponse<?> sync(
        @AuthenticationPrincipal Long actor,
        @PathVariable Long teamId,
        @PathVariable Long installationId,
        HttpServletRequest request) {
        return ok(service.sync(actor, teamId, installationId), request);
    }

    @GetMapping("/integrations/github/callback")
    public ResponseEntity<Void> callback(
        @RequestParam("installation_id") long installationId,
        @RequestParam(required = false) String state,
        @RequestParam(name = "setup_action", required = false) String setupAction) {
        var result = service.callback(installationId, state, setupAction);
        String location =
            UriComponentsBuilder.fromUriString(
                    "MOBILE".equals(result.client()) ? frontendMobile : frontendWeb)
                .pathSegment("app", "integrations", "github")
                .queryParam("teamId", result.teamId())
                .queryParam("installed", "1")
                .build()
                .toUriString();
        return ResponseEntity.status(HttpStatus.FOUND).header("Location", location).build();
    }

    @GetMapping("/teams/{teamId}/integrations/github/repositories")
    public ApiResponse<?> available(
        @AuthenticationPrincipal Long actor,
        @PathVariable Long teamId,
        HttpServletRequest request) {
        return ok(service.listTeamRepositories(actor, teamId), request);
    }

    @GetMapping("/projects/{projectId}/repositories")
    public ApiResponse<?> imported(
        @AuthenticationPrincipal Long actor,
        @PathVariable Long projectId,
        HttpServletRequest request) {
        return ok(service.listProjectRepositories(actor, projectId), request);
    }

    @PostMapping("/projects/{projectId}/repositories")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> importRepository(
        @AuthenticationPrincipal Long actor,
        @PathVariable Long projectId,
        @Valid @RequestBody ImportRequest body,
        HttpServletRequest request) {
        return ok(
            service.importRepository(
                actor, projectId, body.installationId(), body.repositoryId()),
            request);
    }

    @DeleteMapping("/projects/{projectId}/repositories/{repositoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeRepository(
        @AuthenticationPrincipal Long actor,
        @PathVariable Long projectId,
        @PathVariable Long repositoryId) {
        service.removeRepository(actor, projectId, repositoryId);
    }

    private ApiResponse<?> ok(Object value, HttpServletRequest request) {
        return ApiResponse.ok(value, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    /**
     * installationId 是本地安装记录自增 ID，前端以十进制字符串提交。
     */
    public record ImportRequest(
        @NotNull @Schema(type = "string") Long installationId, @Positive long repositoryId) {
    }
}
