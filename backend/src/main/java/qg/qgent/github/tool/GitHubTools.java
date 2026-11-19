package qg.qgent.github.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import qg.qgent.common.web.ApiException;
import qg.qgent.github.client.GitHubApiException;
import qg.qgent.github.service.GitHubRepositoryService;
import qg.qgent.harness.tool.ParallelTool;
import qg.qgent.harness.tool.ToolResult;
import qg.qgent.sandbox.service.SandboxCommands;
import qg.qgent.workspace.service.WorkSession;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent 的 GitHub 工具：具名工具负责仓库、PR 和 Issue 的读取与分页，通用请求负责低频写操作。
 * 所有工具只使用当前项目导入仓库的短期凭证，不返回凭证或凭证环境。
 */
public class GitHubTools {
    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    private static final Pattern REPOSITORY_PATH =
        Pattern.compile("^/repos/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)(?:/|\\?|$)");
    private static final Pattern NEXT_LINK = Pattern.compile("<([^>]+)>;\\s*rel=\"next\"");
    private static final Set<HttpMethod> ALLOWED_METHODS =
        Set.of(
            HttpMethod.GET,
            HttpMethod.POST,
            HttpMethod.PATCH,
            HttpMethod.PUT,
            HttpMethod.DELETE);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DEFAULT_PER_PAGE = 30;

    private final RestClient http;
    private final GitHubRepositoryService repositories;
    private final Long actor;
    private final Long projectId;
    private final SandboxCommands commands;
    private final WorkSession session;

    public GitHubTools(
        RestClient http,
        GitHubRepositoryService repositories,
        Long actor,
        Long projectId,
        SandboxCommands commands,
        WorkSession session) {
        this.http = http;
        this.repositories = repositories;
        this.actor = actor;
        this.projectId = projectId;
        this.commands = commands;
        this.session = session;
    }

    @ParallelTool
    @Tool(
        name = "github_list_repositories",
        description =
            "列出仓库：scope=PROJECT（默认，本项目已导入的仓库）或 scope=TEAM（当前团队 App 安装可访问的仓库，含是否已导入）。"
                + "只读远端清单，不启动沙箱。分页参数 page 从 1 开始，perPage 为 1～100，默认 30；nextPage 为 null 表示没有下一页。")
    public ToolResult listRepositories(
        @ToolParam(description = "PROJECT 或 TEAM，默认 PROJECT", required = false) String scope,
        @ToolParam(description = "页码，从 1 开始", required = false) Integer page,
        @ToolParam(description = "每页条数，1～100，默认 30", required = false) Integer perPage) {
        try {
            boolean teamScope = teamScope(scope);
            int current = page(page);
            int size = perPage(perPage);
            var catalog = repositories.catalog(actor, projectId, teamScope);
            List<GitHubRepositoryService.CatalogRepository> items = catalog.items();
            long from = Math.min((long) (current - 1) * size, items.size());
            long to = Math.min(from + size, items.size());
            ToolResult result = ToolResult.ok();
            result.put("scope", teamScope ? "TEAM" : "PROJECT");
            result.put("connectionStatus", catalog.connectionStatus());
            result.put("message", connectionMessage(catalog.connectionStatus(), teamScope));
            result.put("nextAction", connectionNextAction(catalog.connectionStatus()));
            result.put(
                "items",
                items.subList((int) from, (int) to).stream()
                    .map(this::repositoryItem)
                    .toList());
            result.put("page", current);
            result.put("perPage", size);
            result.put("nextPage", to < items.size() ? current + 1 : null);
            return result;
        } catch (RuntimeException e) {
            return error(e);
        }
    }

    @ParallelTool
    @Tool(
        name = "github_list_pull_requests",
        description =
            "列出当前项目已导入仓库的 Pull Request；state 为 open（默认）、closed 或 all。"
                + "只读远端，不启动沙箱；nextPage 为 null 表示没有下一页。")
    public ToolResult listPullRequests(
        @ToolParam(description = "owner/repo，须已导入当前项目") String repository,
        @ToolParam(description = "open/closed/all，默认 open", required = false) String state,
        @ToolParam(description = "页码，从 1 开始", required = false) Integer page,
        @ToolParam(description = "每页条数，1～100，默认 30", required = false) Integer perPage) {
        try {
            int current = page(page);
            int size = perPage(perPage);
            String issueState = issueState(state);
            var credential = token(repository);
            var raw =
                get(
                    "/repos/"
                        + repository
                        + "/pulls?state="
                        + issueState
                        + "&per_page="
                        + size
                        + "&page="
                        + current,
                    credential.token());
            ToolResult result = ToolResult.ok();
            result.put("repository", repository);
            result.put(
                "items", rows(read(raw.body())).stream().map(this::pullRequestItem).toList());
            result.put("page", current);
            result.put("perPage", size);
            result.put("nextPage", nextPage(raw.link()));
            return result;
        } catch (RuntimeException e) {
            return error(e);
        }
    }

    @ParallelTool
    @Tool(
        name = "github_get_pull_request",
        description = "读取当前项目已导入仓库的单个 Pull Request 详情，包含正文、是否已合并、源分支和目标分支；只读远端。")
    public ToolResult getPullRequest(
        @ToolParam(description = "owner/repo，须已导入当前项目") String repository,
        @ToolParam(description = "Pull Request 编号") Integer number) {
        try {
            int issueNumber = number(number);
            var credential = token(repository);
            var raw = get("/repos/" + repository + "/pulls/" + issueNumber, credential.token());
            ToolResult result = ToolResult.ok();
            result.put("repository", repository);
            result.put("pullRequest", pullRequestDetail(read(raw.body())));
            return result;
        } catch (RuntimeException e) {
            return error(e);
        }
    }

    @ParallelTool
    @Tool(
        name = "github_list_issues",
        description =
            "列出当前项目已导入仓库的 Issue，已排除 Pull Request；state 为 open（默认）、closed 或 all。"
                + "只读远端，不启动沙箱；某页可能因为过滤而变少甚至为空，此时仍以 nextPage 判断是否继续。")
    public ToolResult listIssues(
        @ToolParam(description = "owner/repo，须已导入当前项目") String repository,
        @ToolParam(description = "open/closed/all，默认 open", required = false) String state,
        @ToolParam(description = "页码，从 1 开始", required = false) Integer page,
        @ToolParam(description = "每页条数，1～100，默认 30", required = false) Integer perPage) {
        try {
            int current = page(page);
            int size = perPage(perPage);
            String issueState = issueState(state);
            var credential = token(repository);
            var raw =
                get(
                    "/repos/"
                        + repository
                        + "/issues?state="
                        + issueState
                        + "&per_page="
                        + size
                        + "&page="
                        + current,
                    credential.token());
            ToolResult result = ToolResult.ok();
            result.put("repository", repository);
            result.put(
                "items",
                rows(read(raw.body())).stream()
                    .filter(row -> !row.has("pull_request"))
                    .map(this::issueItem)
                    .toList());
            result.put("page", current);
            result.put("perPage", size);
            result.put("nextPage", nextPage(raw.link()));
            return result;
        } catch (RuntimeException e) {
            return error(e);
        }
    }

    @ParallelTool
    @Tool(
        name = "github_get_issue",
        description = "读取当前项目已导入仓库的单个 Issue 详情，包含正文、标签和指派人；编号属于 Pull Request 时会提示改用 PR 工具。")
    public ToolResult getIssue(
        @ToolParam(description = "owner/repo，须已导入当前项目") String repository,
        @ToolParam(description = "Issue 编号") Integer number) {
        try {
            int issueNumber = number(number);
            var credential = token(repository);
            var raw = get("/repos/" + repository + "/issues/" + issueNumber, credential.token());
            JsonNode issue = read(raw.body());
            if (issue.has("pull_request")) {
                throw invalid("该编号是 Pull Request，请使用 github_get_pull_request");
            }
            ToolResult result = ToolResult.ok();
            result.put("repository", repository);
            result.put("issue", issueDetail(issue));
            return result;
        } catch (RuntimeException e) {
            return error(e);
        }
    }

    @Tool(
        name = "github_request",
        description =
            "对当前项目已导入仓库调用 GitHub REST API，用于创建或修改 PR/Issue、评论、标签等具名工具未覆盖的操作。"
                + "method 只支持 GET/POST/PATCH/PUT/DELETE；path 是相对路径，例如 /repos/owner/repo/pulls，不接受绝对 URL。"
                + "返回 {ok, status, data, pagination.next}，204 时 data 为 null；next 是下一页的相对路径。写请求按用户任务目标决定。")
    public ToolResult request(
        @ToolParam(description = "GET/POST/PATCH/PUT/DELETE") String method,
        @ToolParam(description = "GitHub API 相对路径，可含 query") String path,
        @ToolParam(description = "JSON 请求正文", required = false) Map<String, Object> body) {
        try {
            HttpMethod httpMethod = httpMethod(method);
            if (path == null || path.contains("..") || path.contains("://") || path.contains("#")) {
                throw invalid("不支持的 GitHub API 路径");
            }
            String repository = repositoryFromPath(path);
            if (repository == null) {
                throw invalid("请使用 /repos/owner/repo 路径；仓库清单通过 github_list_repositories 查询");
            }
            var credential = token(repository);
            var raw = exchange(httpMethod, path, credential.token(), body);
            if (raw.status() >= 300) throw raw.failure();
            ToolResult result = ToolResult.ok();
            result.put("status", raw.status());
            result.put(
                "data",
                raw.body().isBlank()
                    ? null
                    : JSON.convertValue(read(raw.body()), Object.class));
            var pagination = new LinkedHashMap<String, Object>();
            pagination.put(
                "next",
                paginationNext(raw.link(), repository, credential.providerRepositoryId()));
            result.put("pagination", pagination);
            return result;
        } catch (RuntimeException e) {
            return error(e);
        }
    }

    @Tool(
        name = "github_push",
        description = "将当前工作区仓库的 HEAD 推送到指定 GitHub 分支，不自动 commit，不创建 PR；按用户目标决定使用。")
    public ToolResult push(
        @ToolParam(description = "owner/repo") String repository,
        @ToolParam(description = "工作区内仓库目录") String directory,
        @ToolParam(description = "远端目标分支") String branch) {
        try {
            var credential = token(repository);
            if (branch == null
                || !branch.matches("[A-Za-z0-9][A-Za-z0-9._/-]*")
                || branch.contains("..")) {
                throw invalid("分支名不合法");
            }
            return commands.run(
                session.ensure(),
                directory,
                List.of(
                    "git",
                    "push",
                    "--",
                    "https://github.com/" + repository + ".git",
                    "HEAD:refs/heads/" + branch),
                GitHubRepositoryService.gitAuthEnvironment(credential.token()));
        } catch (RuntimeException e) {
            return error(e);
        }
    }

    @Tool(
        name = "github_git",
        description =
            "在工作区内执行带当前 Agent GitHub 认证的 Git 参数数组，例如 fetch、pull 或 ls-remote；需指定已导入仓库。"
                + "远端操作按用户目标决定。command 不包含开头的 git。")
    public ToolResult git(
        @ToolParam(description = "例如 [fetch,origin]") List<String> command,
        @ToolParam(description = "工作区内目录", required = false) String directory,
        @ToolParam(description = "目标 owner/repo，必须已导入当前项目") String repository) {
        try {
            var credential = token(repository);
            if (command == null || command.isEmpty()) throw invalid("Git 命令不能为空");
            List<String> argv = new ArrayList<>();
            argv.add("git");
            argv.addAll(command);
            return commands.run(
                session.ensure(),
                directory,
                argv,
                GitHubRepositoryService.gitAuthEnvironment(credential.token()));
        } catch (RuntimeException e) {
            return error(e);
        }
    }

    private Map<String, Object> repositoryItem(
        GitHubRepositoryService.CatalogRepository repository) {
        var item = new LinkedHashMap<String, Object>();
        item.put("providerRepositoryId", Long.toString(repository.providerRepositoryId()));
        item.put("fullName", repository.fullName());
        item.put("defaultBranch", repository.defaultBranch());
        item.put("htmlUrl", repository.htmlUrl());
        item.put("private", repository.privateRepository());
        item.put("imported", repository.imported());
        return item;
    }

    private Map<String, Object> pullRequestItem(JsonNode row) {
        var item = new LinkedHashMap<String, Object>();
        item.put("number", row.path("number").asInt());
        item.put("title", text(row, "title"));
        item.put("state", text(row, "state"));
        item.put("author", row.path("user").path("login").asText(null));
        item.put("head", row.path("head").path("ref").asText(null));
        item.put("base", row.path("base").path("ref").asText(null));
        item.put("htmlUrl", text(row, "html_url"));
        return item;
    }

    private Map<String, Object> pullRequestDetail(JsonNode row) {
        var item = pullRequestItem(row);
        item.put("body", text(row, "body"));
        item.put("merged", row.path("merged").asBoolean(false));
        return item;
    }

    private Map<String, Object> issueItem(JsonNode row) {
        var item = new LinkedHashMap<String, Object>();
        item.put("number", row.path("number").asInt());
        item.put("title", text(row, "title"));
        item.put("state", text(row, "state"));
        item.put("author", row.path("user").path("login").asText(null));
        item.put("labels", labels(row));
        item.put("htmlUrl", text(row, "html_url"));
        return item;
    }

    private Map<String, Object> issueDetail(JsonNode row) {
        var item = issueItem(row);
        item.put("body", text(row, "body"));
        item.put("assignees", assignees(row));
        return item;
    }

    private List<String> labels(JsonNode row) {
        List<String> labels = new ArrayList<>();
        for (JsonNode label : row.path("labels")) labels.add(label.path("name").asText());
        return labels;
    }

    private List<String> assignees(JsonNode row) {
        List<String> assignees = new ArrayList<>();
        for (JsonNode assignee : row.path("assignees"))
            assignees.add(assignee.path("login").asText());
        return assignees;
    }

    private String text(JsonNode row, String field) {
        return row.hasNonNull(field) ? row.get(field).asText() : null;
    }

    private GitHubRepositoryService.RepositoryToken token(String repository) {
        if (repository == null
            || !REPOSITORY.matcher(repository).matches()
            || repository.contains("..")) {
            throw invalid("仓库名称应为 owner/repo");
        }
        return repositories.repositoryToken(actor, projectId, repository);
    }

    private boolean teamScope(String scope) {
        if (scope == null || scope.isBlank()) return false;
        String value = scope.trim().toUpperCase(Locale.ROOT);
        if ("PROJECT".equals(value)) return false;
        if ("TEAM".equals(value)) return true;
        throw invalid("scope 必须为 PROJECT 或 TEAM");
    }

    private String issueState(String state) {
        if (state == null || state.isBlank()) return "open";
        String value = state.trim().toLowerCase(Locale.ROOT);
        if (!List.of("open", "closed", "all").contains(value))
            throw invalid("state 必须为 open、closed 或 all");
        return value;
    }

    private int page(Integer page) {
        if (page == null) return 1;
        if (page < 1) throw invalid("page 必须大于等于 1");
        return page;
    }

    private int perPage(Integer perPage) {
        if (perPage == null) return DEFAULT_PER_PAGE;
        if (perPage < 1 || perPage > 100) throw invalid("perPage 必须为 1～100");
        return perPage;
    }

    private int number(Integer number) {
        if (number == null || number < 1) throw invalid("编号必须为正整数");
        return number;
    }

    private HttpMethod httpMethod(String method) {
        if (method == null || method.isBlank())
            throw invalid("method 必须为 GET/POST/PATCH/PUT/DELETE");
        HttpMethod parsed;
        try {
            parsed = HttpMethod.valueOf(method.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid("method 必须为 GET/POST/PATCH/PUT/DELETE");
        }
        if (!ALLOWED_METHODS.contains(parsed))
            throw invalid("method 必须为 GET/POST/PATCH/PUT/DELETE");
        return parsed;
    }

    private String repositoryFromPath(String path) {
        Matcher matcher = REPOSITORY_PATH.matcher(path);
        return matcher.find() ? matcher.group(1) : null;
    }

    private Raw get(String path, String token) {
        Raw raw = exchange(HttpMethod.GET, path, token, null);
        if (raw.status() >= 300) throw raw.failure();
        return raw;
    }

    private Raw exchange(HttpMethod method, String path, String token, Map<String, Object> body) {
        var request =
            http.method(method)
                .uri(path)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2026-03-10");
        if (body != null) request.body(body);
        try {
            return request.exchange((sent, response) -> {
                String content =
                    new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                var headers = response.getHeaders();
                return new Raw(
                    response.getStatusCode().value(),
                    headers.getFirst("Link"),
                    content,
                    retryAfter(headers.getFirst("Retry-After")),
                    "0".equals(headers.getFirst("X-RateLimit-Remaining")));
            });
        } catch (RestClientException e) {
            throw GitHubApiException.from(e);
        }
    }

    private Long retryAfter(String value) {
        if (value == null) return null;
        try {
            return Math.max(0, Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private JsonNode read(String body) {
        try {
            return JSON.readTree(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("GitHub 响应不是合法 JSON");
        }
    }

    private List<JsonNode> rows(JsonNode body) {
        if (!body.isArray()) throw new IllegalStateException("GitHub 响应不是列表");
        List<JsonNode> rows = new ArrayList<>();
        for (JsonNode row : body) rows.add(row);
        return rows;
    }

    /**
     * Link 中 rel=next 的地址转为相对 API 路径；非 GitHub 地址或含越权片段时丢弃。
     */
    private String nextPath(String link) {
        if (link == null) return null;
        Matcher matcher = NEXT_LINK.matcher(link);
        if (!matcher.find()) return null;
        String url = matcher.group(1);
        int scheme = url.indexOf("://");
        String path = scheme < 0 ? url : url.substring(url.indexOf('/', scheme + 3));
        if (!path.startsWith("/") || path.contains("..") || path.contains("://")) return null;
        return path;
    }

    /**
     * 通用请求的下一页转成 github_request 可直接使用的相对路径：GitHub 可能返回
     * /repositories/{id}/...，必须核对数字 ID 与当前已导入仓库一致，不信任其中的主机或仓库路径。
     */
    private String paginationNext(String link, String repository, long providerRepositoryId) {
        if (link == null) return null;
        Matcher matcher = NEXT_LINK.matcher(link);
        if (!matcher.find()) return null;
        String url = matcher.group(1);
        int scheme = url.indexOf("://");
        if (scheme >= 0 && !url.startsWith("https://api.github.com/")) {
            throw paginationError("下一页链接不是 GitHub API 地址");
        }
        String path = scheme < 0 ? url : url.substring(url.indexOf('/', scheme + 3));
        if (path.contains("..")) throw paginationError("下一页链接不合法");
        String expected = "/repos/" + repository;
        String suffix;
        if (path.startsWith("/repositories/")) {
            String rest = path.substring("/repositories/".length());
            int slash = rest.indexOf('/');
            String id = slash < 0 ? rest : rest.substring(0, slash);
            if (!id.equals(Long.toString(providerRepositoryId)))
                throw paginationError("下一页链接指向其他仓库");
            suffix = slash < 0 ? "" : rest.substring(slash);
        } else if (path.equals(expected)
            || path.startsWith(expected + "/")
            || path.startsWith(expected + "?")) {
            suffix = path.substring(expected.length());
        } else {
            throw paginationError("无法识别下一页链接");
        }
        return expected + suffix;
    }

    private ApiException paginationError(String message) {
        return new ApiException(
            HttpStatus.BAD_GATEWAY,
            "GITHUB_PAGINATION_UNSUPPORTED",
            message + "，请改用对应的列表工具或自行构造查询参数");
    }

    /**
     * 列表接口按 GitHub 的 page 参数翻页；无法解析页码时不下发猜测值。
     */
    private Integer nextPage(String link) {
        String next = nextPath(link);
        if (next == null) return null;
        Matcher matcher = Pattern.compile("[?&]page=(\\d+)").matcher(next);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    private String connectionMessage(String status, boolean teamScope) {
        return switch (status) {
            case "NOT_CONNECTED" -> "团队尚未连接 GitHub App";
            case "ALL_SUSPENDED" -> teamScope
                ? "团队的 GitHub App 安装已全部暂停，列表不含这些安装的仓库"
                : "以下为本项目已导入的仓库记录；对应安装已全部暂停，记录可能过期且无法访问远端";
            case "PARTIALLY_SUSPENDED" -> teamScope
                ? "部分 GitHub App 安装已暂停，列表不含这些安装的仓库"
                : "部分 GitHub App 安装已暂停，以下导入记录可能已过期";
            default -> null;
        };
    }

    private String connectionNextAction(String status) {
        return switch (status) {
            case "NOT_CONNECTED" -> "请团队所有者从团队设置连接 GitHub App 并授权仓库";
            case "ALL_SUSPENDED" -> "请团队所有者在 GitHub 检查安装状态后重新同步";
            default -> null;
        };
    }

    private ToolResult error(RuntimeException exception) {
        if (exception instanceof GitHubApiException failure) return githubError(failure);
        if (exception instanceof ApiException failure) return apiError(failure);
        if (exception instanceof IllegalArgumentException) {
            return errorResult("INVALID_ARGUMENT", exception.getMessage(), null, null, null);
        }
        throw exception;
    }

    private ToolResult apiError(ApiException exception) {
        String code =
            switch (exception.code()) {
                case "PROJECT_NOT_FOUND", "TEAM_RESOURCE_NOT_FOUND" -> "PROJECT_ACCESS_DENIED";
                case "INVALID_GITHUB_ARGUMENT" -> "INVALID_ARGUMENT";
                case "GITHUB_APP_NOT_CONFIGURED", "GITHUB_APP_PRIVATE_KEY_INVALID" -> "GITHUB_NOT_CONFIGURED";
                default -> exception.code();
            };
        return errorResult(
            code, exception.getMessage(), nextAction(code), exception.status().value(), null);
    }

    private ToolResult githubError(GitHubApiException failure) {
        Integer status = failure.httpStatus();
        String code;
        if (status == null) code = "GITHUB_REQUEST_FAILED";
        else if (status == 401) code = "GITHUB_AUTH_FAILED";
        else if (status == 429) code = "GITHUB_RATE_LIMITED";
        else if (status == 403)
            code =
                failure.rateLimited()
                    ? "GITHUB_RATE_LIMITED"
                    : "GITHUB_FORBIDDEN_OR_RATE_LIMITED";
        else if (status == 404) code = "GITHUB_NOT_FOUND_OR_INACCESSIBLE";
        else code = "GITHUB_REQUEST_FAILED";
        return errorResult(
            code, message(code), nextAction(code), status, failure.retryAfterSeconds());
    }

    private String message(String code) {
        return switch (code) {
            case "GITHUB_AUTH_FAILED" -> "GitHub 拒绝了当前 App 凭证";
            case "GITHUB_RATE_LIMITED" -> "GitHub 请求超过速率限制";
            case "GITHUB_FORBIDDEN_OR_RATE_LIMITED" -> "GitHub 拒绝访问，可能是权限不足或触发限流";
            case "GITHUB_NOT_FOUND_OR_INACCESSIBLE" -> "资源不存在或无权访问";
            default -> "GitHub 请求失败";
        };
    }

    private String nextAction(String code) {
        return switch (code) {
            case "PROJECT_ACCESS_DENIED" -> "确认当前账号是该项目的成员";
            case "REPOSITORY_NOT_IMPORTED" -> "请项目管理员在项目仓库设置中导入该仓库";
            case "INSTALLATION_SUSPENDED" -> "请团队所有者在 GitHub 检查安装状态后重新同步";
            case "GITHUB_NOT_CONFIGURED" -> "请管理员配置 GitHub App 后重试";
            case "GITHUB_AUTH_FAILED" -> "请团队所有者重新连接 GitHub App";
            case "GITHUB_RATE_LIMITED", "GITHUB_FORBIDDEN_OR_RATE_LIMITED" -> "检查 App 权限或稍后重试";
            case "GITHUB_NOT_FOUND_OR_INACCESSIBLE" -> "确认仓库名称和 App 授权范围";
            case "GITHUB_REQUEST_FAILED" -> "稍后重试；写请求可能已生效，请先查询确认";
            case "GITHUB_PAGINATION_UNSUPPORTED" -> "请改用对应的列表工具，或按已知参数自行构造下一页请求";
            default -> null;
        };
    }

    private ToolResult errorResult(
        String code,
        String message,
        String nextAction,
        Integer httpStatus,
        Long retryAfterSeconds) {
        ToolResult result = ToolResult.error(code, message);
        if (nextAction != null) result.put("nextAction", nextAction);
        if (httpStatus != null) result.put("httpStatus", httpStatus);
        if (retryAfterSeconds != null) result.put("retryAfterSeconds", retryAfterSeconds);
        return result;
    }

    private IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private record Raw(
        int status, String link, String body, Long retryAfterSeconds, boolean rateLimited) {
        GitHubApiException failure() {
            return new GitHubApiException(status, retryAfterSeconds, rateLimited);
        }
    }
}
