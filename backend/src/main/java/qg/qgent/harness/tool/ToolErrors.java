package qg.qgent.harness.tool;

import qg.qgent.common.web.ApiException;

import java.util.function.Supplier;

/**
 * 工具执行失败映射：业务异常保留错误码，其他运行时失败归入调用方给定的不可用错误码。
 */
public final class ToolErrors {

    public static ToolResult call(
        String unavailableCode, String unavailableMessage, Supplier<ToolResult> action) {
        try {
            return action.get();
        } catch (ApiException e) {
            return ToolResult.error(e.code(), e.getMessage());
        } catch (RuntimeException e) {
            return ToolResult.error(
                unavailableCode, unavailableMessage + ": " + e.getClass().getSimpleName());
        }
    }

    private ToolErrors() {
    }
}
