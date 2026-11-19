package qg.qgent.memory.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 群记忆提炼的触发、重试和批量阈值。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.memory.group")
public class GroupMemoryProperties {
    // 自上次提炼以来至少新增的消息条数
    private int minMessages = 20;
    // 群内最后一次消息后需要静默的分钟数
    private int idleMinutes = 15;
    // 失败后再次尝试的最短间隔（分钟）
    private int retryDelayMinutes = 5;
    // RUNNING 超过该分钟数视为上次进程中断，允许重新领取
    private int staleClaimMinutes = 30;
    // 连续失败次数上限；新消息会清零
    private int maxAttempts = 3;
    // 单次提炼最多送入的消息条数
    private int maxMessagesPerRun = 200;
    // 单次扫描每页读取的群数
    private int scanPageSize = 20;

    public void validate() {
        if (minMessages < 1
            || idleMinutes < 1
            || retryDelayMinutes < 1
            || staleClaimMinutes < 1
            || maxAttempts < 1
            || maxMessagesPerRun < 1
            || scanPageSize < 1) {
            throw new IllegalArgumentException("群记忆提炼配置要求所有阈值大于 0");
        }
    }
}
