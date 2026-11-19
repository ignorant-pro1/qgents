package qg.qgent.notification.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一条通知到一个设备的可恢复推送记录。
 */
@Data
@TableName("push_deliveries")
public class PushDeliveryEntity {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long notificationId;
    private Long deviceId;
    private String status;
    private Integer attemptCount;
    private LocalDateTime nextAttemptAt;
    private String providerMessageId;
    private String lastErrorCode;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime sentAt;
}
