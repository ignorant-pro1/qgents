package qg.qgent.memory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 群聊记忆正文、归属和最后修改时间；只读，由后台提炼任务维护。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupMemoryResponse {

    @Schema(description = "所属项目 ID")
    private String projectId;

    @Schema(description = "项目群聊 ID")
    private String groupId;

    @Schema(description = "群记忆正文；尚未提炼时为空字符串")
    private String content;

    @Schema(description = "最后修改时间（UTC）；尚未提炼时为空")
    private LocalDateTime updatedAt;
}
