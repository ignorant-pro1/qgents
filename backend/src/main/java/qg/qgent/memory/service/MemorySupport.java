package qg.qgent.memory.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 记忆模块公共小工具：模型输出整理，以及后台任务共用的 UTC 时间与失败摘要。
 */
final class MemorySupport {

    private MemorySupport() {
    }

    /**
     * 模型偶尔仍会套代码围栏，写入前去掉，避免记忆正文被围栏污染。
     */
    static String withoutCodeFence(String text) {
        String trimmed = text.strip();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstLineEnd = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        return firstLineEnd < 0 || lastFence <= firstLineEnd
            ? trimmed
            : trimmed.substring(firstLineEnd + 1, lastFence).strip();
    }

    static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /**
     * 失败摘要，截断到状态表列宽。
     */
    static String failure(RuntimeException exception) {
        String message = exception.getMessage();
        String text =
            exception.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
        return text.length() <= 500 ? text : text.substring(0, 500);
    }
}
