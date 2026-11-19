package qg.qgent.sandbox.execution.service;

import org.springframework.stereotype.Component;
import qg.qgent.sandbox.exception.SandboxException;
import qg.qgent.sandbox.execution.dto.ToolContext;
import qg.qgent.sandbox.execution.dto.ToolResult;
import qg.qgent.sandbox.execution.handler.SandboxTool;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;

/**
 * 维护 Agent Sandbox 允许调用的非 Git 工具白名单。
 */
@Component
public class ToolRegistry {
    private final Map<String, SandboxTool> tools;

    public ToolRegistry(List<SandboxTool> handlers) {
        tools =
            handlers.stream()
                .collect(
                    Collectors.toUnmodifiableMap(
                        SandboxTool::name, Function.identity()));
    }

    public ToolResult execute(String name, ToolContext context, Map<String, Object> arguments)
        throws InterruptedException {
        return require(name).execute(context, arguments);
    }

    private SandboxTool require(String name) {
        SandboxTool tool = tools.get(name);
        if (tool == null)
            throw new SandboxException(
                UNPROCESSABLE_ENTITY, "TOOL_NOT_SUPPORTED", "Sandbox 不支持该工具：" + name);
        return tool;
    }
}
