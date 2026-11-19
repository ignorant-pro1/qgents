package qg.qgent.chat.tool;

import qg.qgent.chat.dto.ContextMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天检索工具共用的模型可见摘要：最多 10 条、每条 200 字，全文由读取消息的工具展开。
 */
final class ChatToolOutput {

    private static final int MAX_MESSAGES = 10;
    private static final int MAX_EXCERPT_CHARS = 200;

    static List<Map<String, Object>> digest(List<ContextMessage> messages) {
        List<Map<String, Object>> formatted = new ArrayList<>();
        for (ContextMessage message : messages) {
            if (formatted.size() >= MAX_MESSAGES) {
                break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", message.getId());
            item.put("sequence", message.getSequence());
            item.put("senderType", message.getSenderType());
            item.put("text", excerpt(message.getText()));
            formatted.add(item);
        }
        return formatted;
    }

    static String excerpt(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String singleLine = text.replace('\n', ' ').replace('\r', ' ').strip();
        return singleLine.length() <= MAX_EXCERPT_CHARS
            ? singleLine
            : singleLine.substring(0, MAX_EXCERPT_CHARS) + "...";
    }

    private ChatToolOutput() {
    }
}
