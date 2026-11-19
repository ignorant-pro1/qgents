package qg.qgent.sandbox.execution.dto;

import qg.qgent.sandbox.exception.SandboxException;

import java.util.Map;

import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;

/**
 * 统一校验工具参数，避免各处理器接受任意形状的输入。
 */
public final class ToolArguments {
    private ToolArguments() {
    }

    public static String string(Map<String, Object> arguments, String name, int maxLength) {
        Object value = arguments.get(name);
        if (!(value instanceof String text) || text.isBlank() || text.length() > maxLength) {
            throw invalid(name);
        }
        return text;
    }

    public static String optionalString(
        Map<String, Object> arguments, String name, String fallback, int maxLength) {
        Object value = arguments.get(name);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof String text) || text.length() > maxLength) {
            throw invalid(name);
        }
        return text;
    }

    public static int integer(
        Map<String, Object> arguments, String name, int fallback, int min, int max) {
        Object value = arguments.get(name);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number number)) {
            throw invalid(name);
        }
        if (!Double.isFinite(number.doubleValue())
            || number.doubleValue() != Math.rint(number.doubleValue())
            || number.doubleValue() < min
            || number.doubleValue() > max) throw invalid(name);
        int result = number.intValue();
        if (result < min || result > max) {
            throw invalid(name);
        }
        return result;
    }

    private static SandboxException invalid(String name) {
        return new SandboxException(
            UNPROCESSABLE_ENTITY, "TOOL_ARGUMENT_INVALID", "工具参数不合法：" + name);
    }
}
