package qg.qgent.realtime.transport;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 创建一个 SSE 连接对应的 Spring emitter。
 * <p>
 * 唯一实现是 {@code SseEmitter::new}，保留接缝是为了让 {@code SseEventTransportTest}
 * 能注入会在 send 时失败的 emitter，覆盖断连与连接上限路径。
 */
@FunctionalInterface
public interface SseEmitterFactory {
    SseEmitter create(long timeoutMillis);
}
