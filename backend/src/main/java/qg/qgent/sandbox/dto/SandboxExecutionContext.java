package qg.qgent.sandbox.dto;

import java.util.function.Supplier;

/**
 * 当前工具线程的 AgentRun 身份。
 * <p>
 * 工具回调由框架按固定签名调用，无法携带运行身份，所以身份只在执行工具期间由
 * {@link #within} 设置，沙箱工具在所属线程内读取；业务代码不再各自 set/restore。
 */
public final class SandboxExecutionContext {
    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private SandboxExecutionContext() {
    }

    public static Long currentAgentRunId() {
        return CURRENT.get();
    }

    /**
     * 以给定运行身份执行，结束后恢复线程原有身份。
     */
    public static <T> T within(Long agentRunId, Supplier<T> body) {
        Long previous = CURRENT.get();
        CURRENT.set(agentRunId);
        try {
            return body.get();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
