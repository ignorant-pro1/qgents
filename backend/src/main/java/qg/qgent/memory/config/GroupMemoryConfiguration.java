package qg.qgent.memory.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import qg.qgent.common.config.MdcTaskDecorator;

/**
 * 群记忆提炼线程池与配置；提炼串行执行且不使用聊天回复线程池。
 */
@Configuration
@EnableConfigurationProperties({
    GroupMemoryProperties.class,
    ProjectMemoryDiscoveryProperties.class
})
public class GroupMemoryConfiguration {
    private static final int QUEUE_CAPACITY = 200;

    @Bean(name = "groupMemoryExecutor")
    public ThreadPoolTaskExecutor groupMemoryExecutor(GroupMemoryProperties properties) {
        properties.validate();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("group-memory-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.initialize();
        return executor;
    }
}
