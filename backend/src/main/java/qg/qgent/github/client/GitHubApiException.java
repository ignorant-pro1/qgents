package qg.qgent.github.client;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import qg.qgent.common.web.ApiException;

/**
 * GitHub 请求失败；只在响应确实给出状态或限流头时携带，无法判断时留空，不猜测原因。
 */
public class GitHubApiException extends ApiException {
    private final Integer httpStatus;
    private final Long retryAfterSeconds;
    private final boolean rateLimited;

    public GitHubApiException(Integer httpStatus, Long retryAfterSeconds, boolean rateLimited) {
        // HTTP 调用方沿用既有 502 合同；工具边界读取下面的状态字段给出更具体的错误代码
        super(HttpStatus.BAD_GATEWAY, "GITHUB_APP_REQUEST_FAILED", "GitHub App 请求失败，请检查安装授权或稍后重试");
        this.httpStatus = httpStatus;
        this.retryAfterSeconds = retryAfterSeconds;
        this.rateLimited = rateLimited;
    }

    public static GitHubApiException from(RestClientException exception) {
        if (exception instanceof RestClientResponseException response) {
            HttpHeaders headers = response.getResponseHeaders();
            Long retryAfter = retryAfter(headers);
            boolean exhausted =
                headers != null && "0".equals(headers.getFirst("x-ratelimit-remaining"));
            return new GitHubApiException(
                response.getStatusCode().value(), retryAfter, retryAfter != null || exhausted);
        }
        return new GitHubApiException(null, null, false);
    }

    private static Long retryAfter(HttpHeaders headers) {
        String value = headers == null ? null : headers.getFirst("retry-after");
        if (value == null) return null;
        try {
            return Math.max(0, Long.parseLong(value.trim()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public boolean rateLimited() {
        return rateLimited;
    }
}
