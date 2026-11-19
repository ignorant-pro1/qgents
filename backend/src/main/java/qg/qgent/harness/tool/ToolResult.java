package qg.qgent.harness.tool;

import java.util.LinkedHashMap;

/**
 * 模型可见的工具结果：唯一一处定义扁平结果形状。
 * <p>
 * 工具能报告软失败（模型据此调整下一步）时用本类型；只能成功或抛异常的工具直接返回 payload。
 * 失败结果必须带 errorCode 和 error，其余字段与成功结果一样平铺。
 */
public final class ToolResult extends LinkedHashMap<String, Object> {

    private ToolResult(boolean ok) {
        put("ok", ok);
    }

    public static ToolResult ok() {
        return new ToolResult(true);
    }

    public static ToolResult error(String code, String message) {
        ToolResult result = new ToolResult(false);
        result.put("errorCode", code);
        result.put("error", message);
        return result;
    }
}
