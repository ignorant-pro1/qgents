package qg.qgent.github.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import qg.qgent.common.web.ApiException;
import qg.qgent.github.service.GitHubRepositoryService;
import qg.qgent.harness.tool.ToolResult;
import qg.qgent.sandbox.service.SandboxCommands;
import qg.qgent.support.TestIds;
import qg.qgent.workspace.service.WorkSession;

/** GitHub 工具的返回合同、分页和错误代码；凭证与权限来自服务层。 */
class GitHubToolsTest {
    private static final String API = "https://api.github.com";
    private final Long workspace = TestIds.next();
    private final Long actor = TestIds.next();
    private final Long project = TestIds.next();
    private final SandboxCommands commands = mock(SandboxCommands.class);
    private final GitHubRepositoryService repositories = mock(GitHubRepositoryService.class);
    private final RestClient.Builder builder = RestClient.builder().baseUrl(API);
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final WorkSession session = mock(WorkSession.class);
    private final GitHubTools tools =
            new GitHubTools(builder.build(), repositories, actor, project, commands, session);

    @org.junit.jupiter.api.BeforeEach
    void sessionUsesCurrentWorkspace() {
        when(session.ensure()).thenReturn(workspace);
    }

    @Test
    void springAiCallbacksExposeAllToolsAndSerializeIdsAsStrings() {
        when(repositories.catalog(actor, project, false))
                .thenReturn(
                        new GitHubRepositoryService.Catalog(
                                "ACTIVE", List.of(repository(1234567890123L, "owner/big", true))));
        var callbacks = org.springframework.ai.support.ToolCallbacks.from(tools);
        assertThat(callbacks)
                .extracting(callback -> callback.getToolDefinition().name())
                .contains(
                        "github_list_repositories",
                        "github_list_pull_requests",
                        "github_get_pull_request",
                        "github_list_issues",
                        "github_get_issue",
                        "github_request",
                        "github_push",
                        "github_git");
        var callback =
                java.util.Arrays.stream(callbacks)
                        .filter(item ->
                                item.getToolDefinition().name().equals("github_list_repositories"))
                        .findFirst()
                        .orElseThrow();

        // 可选参数可省略，结果按 Spring AI 的实际序列化输出
        String result = callback.call("{}");

        assertThat(result)
                .contains("\"providerRepositoryId\":\"1234567890123\"")
                .contains("\"nextPage\":null")
                .contains("\"ok\":true");
    }

    @Test
    void projectScopePagesLocalImportsAndReturnsStringIds() {
        when(repositories.catalog(actor, project, false))
                .thenReturn(
                        new GitHubRepositoryService.Catalog(
                                "ACTIVE",
                                List.of(
                                        repository(42L, "owner/a", true),
                                        repository(43L, "owner/b", true),
                                        repository(44L, "owner/c", true))));

        var first = tools.listRepositories(null, 1, 2);

        assertThat(first)
                .containsEntry("ok", true)
                .containsEntry("scope", "PROJECT")
                .containsEntry("connectionStatus", "ACTIVE")
                .containsEntry("nextPage", 2)
                .containsEntry("message", null)
                .containsEntry("nextAction", null);
        assertThat((List<Map<String, Object>>) first.get("items"))
                .hasSize(2)
                .first()
                .satisfies(item ->
                        assertThat(item)
                                .containsEntry("providerRepositoryId", "42")
                                .containsEntry("fullName", "owner/a")
                                .containsEntry("imported", true));

        var second = tools.listRepositories("PROJECT", 2, 2);
        assertThat((List<Map<String, Object>>) second.get("items")).singleElement();
        assertThat(second).containsEntry("nextPage", null);
        verifyNoInteractions(commands);
    }

    @Test
    void teamScopeExplainsMissingConnection() {
        when(repositories.catalog(actor, project, true))
                .thenReturn(new GitHubRepositoryService.Catalog("NOT_CONNECTED", List.of()));

        var result = tools.listRepositories("team", null, null);

        assertThat(result)
                .containsEntry("ok", true)
                .containsEntry("scope", "TEAM")
                .containsEntry("connectionStatus", "NOT_CONNECTED");
        assertThat(result.get("message")).asString().contains("尚未连接");
        assertThat(result.get("nextAction")).asString().contains("团队所有者");
        assertThat((List<?>) result.get("items")).isEmpty();
    }

    @Test
    void pullRequestListUsesLinkForNextPageAndBranchNames() {
        expectToken();
        server.expect(requestTo(API + "/repos/owner/repo/pulls?state=open&per_page=2&page=1"))
                .andExpect(header("Authorization", "Bearer token"))
                .andRespond(
                        withSuccess(
                                        "[" + pullRequest(1) + "," + pullRequest(2) + "]",
                                        MediaType.APPLICATION_JSON)
                                .header(
                                        "Link",
                                        "<"
                                                + API
                                                + "/repositories/1/pulls?state=open&per_page=2&page=2>; rel=\"next\""));

        var result = tools.listPullRequests("owner/repo", null, null, 2);

        assertThat(result).containsEntry("ok", true).containsEntry("nextPage", 2);
        assertThat((List<Map<String, Object>>) result.get("items"))
                .hasSize(2)
                .first()
                .satisfies(item ->
                        assertThat(item)
                                .containsEntry("number", 1)
                                .containsEntry("head", "feature")
                                .containsEntry("base", "main")
                                .containsEntry("author", "octocat"));
        server.verify();
    }

    @Test
    void lastPageWithoutLinkHasNoNextPage() {
        expectToken();
        server.expect(requestTo(API + "/repos/owner/repo/pulls?state=closed&per_page=30&page=1"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        var result = tools.listPullRequests("owner/repo", "closed", null, null);

        assertThat(result).containsEntry("nextPage", null);
        assertThat((List<?>) result.get("items")).isEmpty();
    }

    @Test
    void issueListFiltersPullRequestsButKeepsOriginalNextPage() {
        expectToken();
        server.expect(requestTo(API + "/repos/owner/repo/issues?state=all&per_page=30&page=1"))
                .andRespond(
                        withSuccess("[" + pullRequest(3) + "]", MediaType.APPLICATION_JSON)
                                .header(
                                        "Link",
                                        "<"
                                                + API
                                                + "/repositories/1/issues?state=all&per_page=30&page=2>; rel=\"next\""));

        var result = tools.listIssues("owner/repo", "all", null, null);

        assertThat((List<?>) result.get("items")).isEmpty();
        assertThat(result).containsEntry("nextPage", 2);
    }

    @Test
    void issueDetailRejectsPullRequestNumber() {
        expectToken();
        server.expect(requestTo(API + "/repos/owner/repo/issues/7"))
                .andRespond(withSuccess(pullRequest(7), MediaType.APPLICATION_JSON));

        var result = tools.getIssue("owner/repo", 7);

        assertThat(result).containsEntry("ok", false);
        assertThat(error(result)).containsEntry("errorCode", "INVALID_ARGUMENT");
        assertThat(error(result).get("error")).asString().contains("Pull Request");
    }

    @Test
    void issueDetailReturnsLabelsAndAssignees() {
        expectToken();
        server.expect(requestTo(API + "/repos/owner/repo/issues/5"))
                .andRespond(
                        withSuccess(
                                "{\"number\":5,\"title\":\"bug\",\"body\":\"detail\",\"state\":\"open\",\"user\":{\"login\":\"octocat\"},"
                                        + "\"labels\":[{\"name\":\"bug\"}],\"assignees\":[{\"login\":\"hubot\"}],"
                                        + "\"html_url\":\"https://github.com/owner/repo/issues/5\"}",
                                MediaType.APPLICATION_JSON));

        var issue = (Map<String, Object>) tools.getIssue("owner/repo", 5).get("issue");

        assertThat(issue).containsEntry("number", 5).containsEntry("body", "detail");
        assertThat((List<String>) issue.get("labels")).containsExactly("bug");
        assertThat((List<String>) issue.get("assignees")).containsExactly("hubot");
    }

    @Test
    void pullRequestDetailReportsMergedState() {
        expectToken();
        server.expect(requestTo(API + "/repos/owner/repo/pulls/12"))
                .andRespond(
                        withSuccess(
                                "{\"number\":12,\"title\":\"change\",\"body\":\"text\",\"state\":\"closed\",\"merged\":true,"
                                        + "\"user\":{\"login\":\"octocat\"},\"head\":{\"ref\":\"feature\"},\"base\":{\"ref\":\"main\"},"
                                        + "\"html_url\":\"https://github.com/owner/repo/pull/12\"}",
                                MediaType.APPLICATION_JSON));

        var pullRequest =
                (Map<String, Object>) tools.getPullRequest("owner/repo", 12).get("pullRequest");

        assertThat(pullRequest)
                .containsEntry("merged", true)
                .containsEntry("state", "closed")
                .containsEntry("head", "feature");
    }

    @Test
    void requestNormalizesResponseAndRewritesGithubRepositoryLinks() {
        expectToken();
        server.expect(requestTo(API + "/repos/owner/repo/pulls"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(
                        content()
                                .json("{\"title\":\"change\",\"head\":\"feat\",\"base\":\"main\"}"))
                .andRespond(
                        withSuccess("{\"number\":12}", MediaType.APPLICATION_JSON)
                                .header(
                                        "Link",
                                        "<"
                                                + API
                                                + "/repositories/42/pulls?state=all&cursor=abc>; rel=\"next\""));

        var result =
                tools.request(
                        "POST",
                        "/repos/owner/repo/pulls",
                        Map.of("title", "change", "head", "feat", "base", "main"));

        assertThat(result).containsEntry("ok", true).containsEntry("status", 200);
        assertThat((Map<String, Object>) result.get("data")).containsEntry("number", 12);
        assertThat((Map<String, Object>) result.get("pagination"))
                .containsEntry("next", "/repos/owner/repo/pulls?state=all&cursor=abc");
        server.verify();
    }

    @Test
    void paginationNextCanBePassedBackToRequest() {
        expectToken();
        server.expect(requestTo(API + "/repos/owner/repo/pulls?state=all"))
                .andRespond(
                        withSuccess("[]", MediaType.APPLICATION_JSON)
                                .header(
                                        "Link",
                                        "<"
                                                + API
                                                + "/repositories/42/pulls?state=all&page=2>; rel=\"next\""));
        server.expect(requestTo(API + "/repos/owner/repo/pulls?state=all&page=2"))
                .andExpect(header("Authorization", "Bearer token"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        var first = tools.request("GET", "/repos/owner/repo/pulls?state=all", null);
        var next = (String) ((Map<String, Object>) first.get("pagination")).get("next");
        var second = tools.request("GET", next, null);

        assertThat(next).isEqualTo("/repos/owner/repo/pulls?state=all&page=2");
        assertThat(second).containsEntry("ok", true).containsEntry("status", 200);
        assertThat((Map<String, Object>) second.get("pagination")).containsEntry("next", null);
        server.verify();
    }

    @Test
    void paginationNextForAnotherRepositoryOrHostIsRejected() {
        expectToken();
        server.expect(requestTo(API + "/repos/owner/repo/pulls"))
                .andRespond(
                        withSuccess("[]", MediaType.APPLICATION_JSON)
                                .header(
                                        "Link",
                                        "<"
                                                + API
                                                + "/repositories/99/pulls?page=2>; rel=\"next\""));
        server.expect(requestTo(API + "/repos/owner/repo/issues"))
                .andRespond(
                        withSuccess("[]", MediaType.APPLICATION_JSON)
                                .header(
                                        "Link",
                                        "<https://evil.example/repos/owner/repo/issues?page=2>; rel=\"next\""));

        assertThat(error(tools.request("GET", "/repos/owner/repo/pulls", null)))
                .containsEntry("errorCode", "GITHUB_PAGINATION_UNSUPPORTED");
        assertThat(error(tools.request("GET", "/repos/owner/repo/issues", null)))
                .containsEntry("errorCode", "GITHUB_PAGINATION_UNSUPPORTED");
        server.verify();
    }

    @Test
    void requestWithoutBodyReturnsNullData() {
        expectToken();
        server.expect(requestTo(API + "/repos/owner/repo/labels/x"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        var result = tools.request("DELETE", "/repos/owner/repo/labels/x", null);

        assertThat(result)
                .containsEntry("ok", true)
                .containsEntry("status", 204)
                .containsEntry("data", null);
        assertThat((Map<String, Object>) result.get("pagination")).containsEntry("next", null);
    }

    @Test
    void rateLimitedResponseKeepsStatusAndRetryAfter() {
        expectToken();
        server.expect(requestTo(API + "/repos/owner/repo"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "30"));

        var error = error(tools.request("GET", "/repos/owner/repo", null));

        assertThat(error)
                .containsEntry("errorCode", "GITHUB_RATE_LIMITED")
                .containsEntry("httpStatus", 429)
                .containsEntry("retryAfterSeconds", 30L);
    }

    @Test
    void forbiddenResponseWithoutEvidenceIsNotReportedAsRateLimit() {
        expectToken();
        server.expect(requestTo(API + "/repos/owner/repo"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        var error = error(tools.request("GET", "/repos/owner/repo", null));

        assertThat(error)
                .containsEntry("errorCode", "GITHUB_FORBIDDEN_OR_RATE_LIMITED")
                .containsEntry("httpStatus", 403)
                .doesNotContainKey("retryAfterSeconds");
    }

    @Test
    void unauthorizedAndMissingResourcesUseDistinctCodes() {
        expectToken();
        server.expect(requestTo(API + "/repos/owner/repo/pulls?state=open&per_page=30&page=1"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo(API + "/repos/owner/repo/issues?state=open&per_page=30&page=1"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(error(tools.listPullRequests("owner/repo", null, null, null)))
                .containsEntry("errorCode", "GITHUB_AUTH_FAILED");
        assertThat(error(tools.listIssues("owner/repo", null, null, null)))
                .containsEntry("errorCode", "GITHUB_NOT_FOUND_OR_INACCESSIBLE");
        server.verify();
    }

    @Test
    void networkFailureReportsUnknownOutcomeWithoutRetry() {
        expectToken();
        server.expect(requestTo(API + "/repos/owner/repo/pulls?state=open&per_page=30&page=1"))
                .andRespond(withException(new java.io.IOException("connection reset")));

        var error = error(tools.listPullRequests("owner/repo", null, null, null));

        assertThat(error)
                .containsEntry("errorCode", "GITHUB_REQUEST_FAILED")
                .doesNotContainKey("httpStatus");
        assertThat(error.get("nextAction")).asString().contains("先查询确认");
    }

    @Test
    void serverErrorKeepsHttpStatus() {
        expectToken();
        server.expect(requestTo(API + "/repos/owner/repo/pulls?state=open&per_page=30&page=1"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThat(error(tools.listPullRequests("owner/repo", null, null, null)))
                .containsEntry("errorCode", "GITHUB_REQUEST_FAILED")
                .containsEntry("httpStatus", 502);
    }

    @Test
    void missingImportAndAccessDeniedUseToolContract() {
        when(repositories.repositoryToken(actor, project, "owner/repo"))
                .thenThrow(
                        new ApiException(
                                HttpStatus.BAD_REQUEST,
                                "REPOSITORY_NOT_IMPORTED",
                                "请先将目标仓库导入当前项目"));
        when(repositories.catalog(actor, project, false))
                .thenThrow(
                        new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在或不可见"));

        var notImported = error(tools.listPullRequests("owner/repo", null, null, null));
        var denied = error(tools.listRepositories(null, null, null));

        assertThat(notImported)
                .containsEntry("errorCode", "REPOSITORY_NOT_IMPORTED")
                .containsEntry("httpStatus", 400);
        assertThat(notImported.get("nextAction")).asString().contains("项目管理员");
        assertThat(denied)
                .containsEntry("errorCode", "PROJECT_ACCESS_DENIED")
                .containsEntry("httpStatus", 404);
    }

    @Test
    void unconfiguredAppIsReportedAsConfigurationProblem() {
        when(repositories.repositoryToken(actor, project, "owner/repo"))
                .thenThrow(
                        new ApiException(
                                HttpStatus.SERVICE_UNAVAILABLE,
                                "GITHUB_APP_NOT_CONFIGURED",
                                "GitHub App 尚未配置"));

        var error = error(tools.listPullRequests("owner/repo", null, null, null));

        assertThat(error)
                .containsEntry("errorCode", "GITHUB_NOT_CONFIGURED")
                .containsEntry("httpStatus", 503);
        assertThat(error.get("nextAction")).asString().contains("管理员");
    }

    @Test
    void invalidArgumentsAreRejectedBeforeAnyRequest() {
        assertThat(error(tools.listRepositories("ORG", null, null)))
                .containsEntry("errorCode", "INVALID_ARGUMENT");
        assertThat(error(tools.listRepositories(null, 0, null)))
                .containsEntry("errorCode", "INVALID_ARGUMENT");
        assertThat(error(tools.listPullRequests("owner/repo", null, null, 101)))
                .containsEntry("errorCode", "INVALID_ARGUMENT");
        assertThat(error(tools.getIssue("owner/repo", 0)))
                .containsEntry("errorCode", "INVALID_ARGUMENT");
        assertThat(error(tools.request("TRACE", "/repos/owner/repo", null)))
                .containsEntry("errorCode", "INVALID_ARGUMENT");
        assertThat(error(tools.request("GET", "https://evil.example/repos/owner/repo", null)))
                .containsEntry("errorCode", "INVALID_ARGUMENT");
        assertThat(error(tools.request("GET", "/user/repos", null)))
                .containsEntry("errorCode", "INVALID_ARGUMENT");
        server.verify();
        verifyNoInteractions(repositories, commands);
    }

    @Test
    void gitUsesTransientAuthenticationAndReturnsCommandResult() {
        expectToken();
        var failure = ToolResult.ok();
        failure.put("exitCode", 1);
        failure.put("stderr", "permission denied");
        when(commands.run(any(), anyString(), anyList(), anyMap())).thenReturn(failure);

        assertThat(tools.git(List.of("fetch", "origin"), ".", "owner/repo")).isEqualTo(failure);
        verify(commands)
                .run(
                        eq(workspace),
                        eq("."),
                        eq(List.of("git", "fetch", "origin")),
                        argThat(env ->
                                env.get("GIT_CONFIG_VALUE_0").startsWith("AUTHORIZATION: basic ")));

        assertThat(tools.push("owner/repo", "backend", "feat/change")).isEqualTo(failure);
        verify(commands)
                .run(
                        eq(workspace),
                        eq("backend"),
                        eq(
                                List.of(
                                        "git",
                                        "push",
                                        "--",
                                        "https://github.com/owner/repo.git",
                                        "HEAD:refs/heads/feat/change")),
                        anyMap());
    }

    private void expectToken() {
        when(repositories.repositoryToken(actor, project, "owner/repo"))
                .thenReturn(new GitHubRepositoryService.RepositoryToken("token", 42L));
    }

    private GitHubRepositoryService.CatalogRepository repository(
            long id, String fullName, boolean imported) {
        return new GitHubRepositoryService.CatalogRepository(
                id, fullName, "main", "https://github.com/" + fullName, false, imported);
    }

    private String pullRequest(int number) {
        return "{\"number\":"
                + number
                + ",\"title\":\"pr "
                + number
                + "\",\"state\":\"open\","
                + "\"user\":{\"login\":\"octocat\"},\"head\":{\"ref\":\"feature\"},\"base\":{\"ref\":\"main\"},"
                + "\"pull_request\":{\"url\":\"x\"},\"html_url\":\"https://github.com/owner/repo/pull/"
                + number
                + "\"}";
    }

    /** 失败结果是扁平 envelope：errorCode/error 与可选 nextAction/httpStatus 同层。 */
    private Map<String, Object> error(Map<String, Object> result) {
        assertThat(result).containsEntry("ok", false);
        return result;
    }
}
