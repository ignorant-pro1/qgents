package qg.qgent.sandbox.execution.handler;

import qg.qgent.sandbox.execution.dto.ToolContext;
import qg.qgent.sandbox.execution.dto.ToolResult;

import java.util.Map;

/**
 * 服务端注册的单个沙箱工具。
 */
public interface SandboxTool {
    String name();

    ToolResult execute(ToolContext context, Map<String, Object> arguments)
        throws InterruptedException;
}
