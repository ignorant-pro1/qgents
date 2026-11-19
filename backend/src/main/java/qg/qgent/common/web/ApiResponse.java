package qg.qgent.common.web;

/**
 * 统一 API 响应，包含业务数据和当前请求 ID。
 */
public record ApiResponse<T>(T data, String requestId) {
    public static <T> ApiResponse<T> ok(T data, String requestId) {
        return new ApiResponse<>(data, requestId);
    }
}
