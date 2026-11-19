package qg.qgent.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

/**
 * 在鉴权和业务处理前设置请求 ID，记录访问耗时，并在请求结束后清理 MDC。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE) // 最先执行
@Slf4j
public class RequestIdFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE = "requestId";

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String id = "req_" + UUID.randomUUID();
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader("X-Request-Id", id);
        MDC.put(ATTRIBUTE, id);
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            // 访问日志：方法、URI、状态码、耗时；SSE 等长连接在连接结束时才输出，耗时即连接时长。
            log.info(
                "{} {} -> {} ({}ms)",
                request.getMethod(),
                request.getRequestURI(),
                response.getStatus(),
                Duration.ofNanos(System.nanoTime() - started).toMillis());
            // 请求线程会被复用；结束时清理 MDC，避免请求 ID 带入下一次请求。
            MDC.remove(ATTRIBUTE);
        }
    }
}
