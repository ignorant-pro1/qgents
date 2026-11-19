package qg.qgent.harness.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 线程池容量配置。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "qgents.executor.agent")
public class AgentExecutorProperties {
    // 常驻线程数
    private int coreSize = 2;
    // 最大线程数
    private int maxSize = 4;
    // 等待队列长度
    private int queueCapacity = 100;

    public void validate() {
        if (coreSize < 1 || maxSize < coreSize || queueCapacity < 0) {
            throw new IllegalArgumentException(
                "Agent 执行器要求 coreSize>=1、maxSize>=coreSize、queueCapacity>=0");
        }
    }
}
