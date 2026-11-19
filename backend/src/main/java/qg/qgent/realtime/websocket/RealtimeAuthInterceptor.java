package qg.qgent.realtime.websocket;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;
import qg.qgent.auth.service.TokenService;

import java.util.Map;

/**
 * 校验握手 URL 中的 access token，并把用户 ID 保存到会话。
 */
@Component
public class RealtimeAuthInterceptor implements HandshakeInterceptor {
    // 会话属性中存放已鉴权用户 ID 的键。
    public static final String USER_ID_ATTR = "realtimeUserId";

    private final TokenService tokenService;

    public RealtimeAuthInterceptor(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public boolean beforeHandshake(
        ServerHttpRequest request,
        ServerHttpResponse response,
        WebSocketHandler wsHandler,
        Map<String, Object> attributes) {
        String token =
            UriComponentsBuilder.fromUri(request.getURI())
                .build()
                .getQueryParams()
                .getFirst("token");
        Long userId = token == null ? null : tokenService.verifyAccess(token);
        if (userId == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(USER_ID_ATTR, userId);
        return true;
    }

    @Override
    public void afterHandshake(
        ServerHttpRequest request,
        ServerHttpResponse response,
        WebSocketHandler wsHandler,
        Exception exception) {
        // 默认实现即可：beforeHandshake 已处理鉴权决策
    }
}
