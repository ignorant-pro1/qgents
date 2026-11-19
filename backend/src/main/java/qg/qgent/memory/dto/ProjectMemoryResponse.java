package qg.qgent.memory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 项目长期记忆 memory.md 的内容和最后修改时间。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProjectMemoryResponse {

    @Schema(description = "所属项目 ID")
    private String projectId;

    @Schema(description = "memory.md 全文")
    private String content;

    @Schema(description = "最后修改时间（UTC）；项目尚未写入时为空")
    private LocalDateTime updatedAt;
}
