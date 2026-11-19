package qg.qgent.harness.tool;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 保留 Spring AI 的工具 schema，将方法上的并行标记交给项目 loop。
 */
public record ParallelToolCallback(ToolCallback delegate) implements ToolCallback {
    public static ToolCallback[] from(Object... objects) {
        Set<String> parallel = new HashSet<>();
        for (Object object : objects) {
            for (var method : object.getClass().getMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool != null && method.isAnnotationPresent(ParallelTool.class)) {
                    parallel.add(tool.name().isBlank() ? method.getName() : tool.name());
                }
            }
        }
        return Arrays.stream(ToolCallbacks.from(objects))
            .map(callback ->
                parallel.contains(callback.getToolDefinition().name())
                    ? new ParallelToolCallback(callback)
                    : callback)
            .toArray(ToolCallback[]::new);
    }

    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    public String call(String input) {
        return delegate.call(input);
    }

    public String call(String input, ToolContext context) {
        return delegate.call(input, context);
    }
}
