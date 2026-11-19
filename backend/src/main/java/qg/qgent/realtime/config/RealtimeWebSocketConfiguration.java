package qg.qgent.realtime.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import qg.qgent.realtime.websocket.RealtimeAuthInterceptor;
import qg.qgent.realtime.websocket.WebSocketRealtimeHandler;

/**
 * 注册统一 WebSocket 事件接口及握手鉴权。
 */
@Configuration
@EnableWebSocket
public class RealtimeWebSocketConfiguration implements WebSocketConfigurer {

    private final WebSocketRealtimeHandler realtimeHandler;
    private final RealtimeAuthInterceptor authInterceptor;

    public RealtimeWebSocketConfiguration(
        WebSocketRealtimeHandler realtimeHandler, RealtimeAuthInterceptor authInterceptor) {
        this.realtimeHandler = realtimeHandler;
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(realtimeHandler, "/api/v1/ws/realtime")
            .addInterceptors(authInterceptor)
            .setAllowedOrigins("*");
    }
}
