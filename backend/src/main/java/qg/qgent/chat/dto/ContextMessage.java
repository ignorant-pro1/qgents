package qg.qgent.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 上下文中的消息，保留消息标识、回复关系和发送者；图片及文件包含供按需加载的附件信息。
 */
@Data
@NoArgsConstructor
public class ContextMessage {

    private String id;
    private String replyToId;

    // 群内单调递增序号。
    @Schema(description = "群内单调递增序号")
    private Long sequence;

    // 消息类型：TEXT/CODE/IMAGE/FILE 等。
    @Schema(description = "消息类型")
    private String type;

    // 发送者类型：USER / AGENT / SYSTEM。
    @Schema(description = "发送者类型：USER / AGENT / SYSTEM")
    private String senderType;

    // 发送者 ID（用户或 Agent），可为空。
    @Schema(description = "发送者 ID（用户或 Agent）")
    private String senderId;

    // 消息正文（从结构化 content 提取的文本；IMAGE/FILE 无正文时为空串，由渲染层生成附件引用）。
    @Schema(description = "消息正文")
    private String text;

    // 附件 ID（IMAGE/FILE 消息，十进制字符串）；其他类型为空。需要内容时用 read_attachment 读取。
    @Schema(description = "附件 ID（IMAGE/FILE 消息）")
    private String attachmentId;

    // 附件原始文件名（IMAGE/FILE 消息），可为空。
    @Schema(description = "附件原始文件名（IMAGE/FILE 消息）")
    private String fileName;

    // 附件 MIME 类型（IMAGE/FILE 消息），可为空。
    @Schema(description = "附件 MIME 类型（IMAGE/FILE 消息）")
    private String mediaType;

    // 消息声明的附件大小（字节），仅供展示；实际读取以服务端附件记录为准。
    @Schema(description = "附件大小（字节，仅展示）")
    private Long size;

    public ContextMessage(
        Long sequence, String type, String senderType, String senderId, String text) {
        this(sequence, type, senderType, senderId, text, null, null, null, null);
    }

    public ContextMessage(
        Long sequence,
        String type,
        String senderType,
        String senderId,
        String text,
        String attachmentId,
        String fileName,
        String mediaType,
        Long size) {
        this.sequence = sequence;
        this.type = type;
        this.senderType = senderType;
        this.senderId = senderId;
        this.text = text;
        this.attachmentId = attachmentId;
        this.fileName = fileName;
        this.mediaType = mediaType;
        this.size = size;
    }
}
