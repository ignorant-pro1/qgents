package qg.qgent.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import qg.qgent.auth.service.TokenService;
import qg.qgent.common.web.RequestIdFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final TokenService tokens;
    private final ObjectMapper mapper;

    public JwtAuthenticationFilter(TokenService tokens, ObjectMapper mapper) {
        this.tokens = tokens;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        // ASYNC dispatch 是已通过初始鉴权的请求（如 SSE 流）断连/超时后的收尾派发；
        // 此时长连接可能已超出 access token 有效期，重复解析 JWT 会对已提交响应写 401。
        // 派发类型已在 SecurityConfiguration 中按 DispatcherType.ASYNC 放行。
        return true;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {
        // 预览供 img/iframe 直接访问，仅统一内容 GET 路径允许 query token；Bearer 优先。
        String header = req.getHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
        if (token == null
            && "GET".equals(req.getMethod())
            && req.getRequestURI()
            .substring(req.getContextPath().length())
            .matches("/api/v1/projects/[^/]+/attachments/[^/]+/content")) {
            token = req.getParameter("token");
        }
        if (token != null && !token.isBlank()) {
            var userId = tokens.verifyAccess(token);
            if (userId == null) {
                unauthorized(req, res);
                return;
            }
            SecurityContextHolder.getContext()
                .setAuthentication(
                    new UsernamePasswordAuthenticationToken(userId, null, List.of()));
        }
        chain.doFilter(req, res);
    }

    // 处理未授权的请求，返回401错误
    private void unauthorized(HttpServletRequest req, HttpServletResponse res) throws IOException {
        // 响应已提交（如 SSE 流式响应断连派发）时无法再写错误体，避免二次异常
        if (res.isCommitted()) {
            return;
        }
        res.setStatus(401);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(
            res.getWriter(),
            Map.of(
                "error",
                Map.of(
                    "code",
                    "INVALID_ACCESS_TOKEN",
                    "message",
                    "access token无效或已过期",
                    "details",
                    List.of()),
                "requestId",
                String.valueOf(req.getAttribute(RequestIdFilter.ATTRIBUTE))));
    }
}
