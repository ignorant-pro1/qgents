package qg.qgent.sandbox.execution.entity;

/**
 * tool_executions.status 的取值；数据库按枚举名存字符串。
 */
public enum ExecutionStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED,
    INTERRUPTED;

    /**
     * 已结束，不会再变化。
     */
    public boolean terminal() {
        return this != QUEUED && this != RUNNING;
    }
}
