package qg.qgent.realtime.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import qg.qgent.common.config.MdcTaskDecorator;
import qg.qgent.realtime.transport.SseEmitterFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 配置 SSE 连接泵的执行器和 emitter 工厂。
 */
@Configuration(proxyBeanMethods = false)
public class SseTransportConfiguration {

    /**
     * 每条连接长期占用一个线程，用虚拟线程让 128 条连接不必排队等待线程。
     */
    @Bean(name = "sseEventPumpExecutor", destroyMethod = "shutdown")
    public ExecutorService sseEventPumpExecutor() {
        MdcTaskDecorator mdcDecorator = new MdcTaskDecorator();
        ThreadFactory factory =
            runnable ->
                Thread.ofVirtual()
                    .name("sse-event-pump-", 1)
                    .unstarted(mdcDecorator.decorate(runnable));
        return Executors.newThreadPerTaskExecutor(factory);
    }

    @Bean
    public SseEmitterFactory sseEmitterFactory() {
        return SseEmitter::new;
    }
}
