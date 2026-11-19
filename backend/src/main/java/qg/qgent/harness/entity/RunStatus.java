package qg.qgent.harness.entity;

/**
 * agent_runs.status 的取值；数据库按枚举名存字符串。
 */
public enum RunStatus {
    RUNNING,
    CANCELLING,
    CANCELLED,
    SUCCEEDED,
    FAILED;

    /**
     * 取消已请求或已生效。
     */
    public boolean cancelled() {
        return this == CANCELLING || this == CANCELLED;
    }
}
