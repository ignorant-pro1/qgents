package qg.qgent.memory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import qg.qgent.memory.entity.GroupMemoryStateEntity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 群记忆提炼状态的数据访问：领取、进度推进和失败重试都在一条语句内完成，避免并发覆盖。
 */
@Mapper
public interface GroupMemoryStateMapper extends BaseMapper<GroupMemoryStateEntity> {

    /**
     * 按键游标分批遍历提炼状态：只包含空闲状态或 RUNNING 已超过领取有效期（上次进程中断）的行，
     * 按 group_id 升序。游标保证每轮扫完所有群，修好的群不会永远占住窗口；
     * 其余触发条件（闲置时长、消息数、重试次数）由服务层判断。
     */
    @Select({
        "<script>",
        "SELECT group_id, project_id, last_processed_sequence, last_message_at, status, claim_token, ",
        "attempt_count, next_attempt_at, correction, last_error, updated_at FROM group_memory_state ",
        "WHERE (status = 'IDLE' OR updated_at &lt; #{staleBefore}) ",
        "<if test='afterGroupId != null'>AND group_id &gt; #{afterGroupId} </if>",
        "ORDER BY group_id LIMIT #{limit}",
        "</script>"
    })
    List<GroupMemoryStateEntity> selectCandidates(
        @Param("staleBefore") LocalDateTime staleBefore,
        @Param("afterGroupId") Long afterGroupId,
        @Param("limit") int limit);

    /**
     * 记录一条新消息：置活跃时间并清零连续失败次数；消息水位只由成功提炼推进。
     */
    @Insert(
        "INSERT INTO group_memory_state (group_id, project_id, last_message_at, last_processed_sequence, "
            + "status, attempt_count, updated_at) VALUES (#{groupId}, #{projectId}, #{at}, 0, 'IDLE', 0, #{at}) "
            + "ON DUPLICATE KEY UPDATE last_message_at = #{at}, attempt_count = 0, next_attempt_at = NULL, "
            + "updated_at = #{at}")
    int recordActivity(
        @Param("projectId") Long projectId,
        @Param("groupId") Long groupId,
        @Param("at") LocalDateTime at);

    /**
     * 累积一次群记忆纠错；闲置计时重新开始，等待讨论暂停后再提炼。
     */
    @Insert(
        "INSERT INTO group_memory_state (group_id, project_id, correction, last_message_at, "
            + "last_processed_sequence, status, attempt_count, updated_at) "
            + "VALUES (#{groupId}, #{projectId}, #{correction}, #{at}, 0, 'IDLE', 0, #{at}) "
            + "ON DUPLICATE KEY UPDATE correction = CASE WHEN correction IS NULL OR correction = '' "
            + "THEN #{correction} ELSE CONCAT(correction, CHAR(10), #{correction}) END, "
            + "last_message_at = #{at}, attempt_count = 0, next_attempt_at = NULL, updated_at = #{at}")
    int requestCorrection(
        @Param("projectId") Long projectId,
        @Param("groupId") Long groupId,
        @Param("correction") String correction,
        @Param("at") LocalDateTime at);

    /**
     * 领取提炼任务并写入领取标识；返回 0 表示其他任务已领取，本次必须放弃。
     */
    @Update(
        "UPDATE group_memory_state SET status = 'RUNNING', claim_token = #{now}, updated_at = #{now} "
            + "WHERE group_id = #{groupId} AND (status = 'IDLE' OR updated_at < #{staleBefore})")
    int claim(
        @Param("groupId") Long groupId,
        @Param("now") LocalDateTime now,
        @Param("staleBefore") LocalDateTime staleBefore);

    /**
     * 写结果前确认领取仍然有效；返回 0 表示已被新任务接管，本次结果必须丢弃。
     */
    @Update(
        "UPDATE group_memory_state SET updated_at = #{now} "
            + "WHERE group_id = #{groupId} AND status = 'RUNNING' AND claim_token = #{claimToken}")
    int renewClaim(
        @Param("groupId") Long groupId,
        @Param("claimToken") LocalDateTime claimToken,
        @Param("now") LocalDateTime now);

    /**
     * 提炼成功后推进水位，并只清空本次实际应用的纠错；水位只增不减，旧任务的完成不会覆盖新进度。
     * 提炼期间新增的纠错文本与本次读到的不同，保留待下一次处理。
     * 群记忆更新后项目记忆发现重新获得重试次数：新内容值得再试一次。
     */
    @Update(
        "UPDATE group_memory_state SET status = 'IDLE', claim_token = NULL, "
            + "last_processed_sequence = GREATEST(last_processed_sequence, #{processedThrough}), "
            + "attempt_count = 0, next_attempt_at = NULL, last_error = NULL, updated_at = #{now}, "
            + "discovery_attempt_count = 0, "
            + "correction = CASE WHEN correction = #{appliedCorrection} THEN NULL ELSE correction END "
            + "WHERE group_id = #{groupId} AND status = 'RUNNING' AND claim_token = #{claimToken}")
    int finishSuccess(
        @Param("groupId") Long groupId,
        @Param("claimToken") LocalDateTime claimToken,
        @Param("appliedCorrection") String appliedCorrection,
        @Param("processedThrough") Long processedThrough,
        @Param("now") LocalDateTime now);

    /**
     * 提炼失败：保持原水位和待应用纠错不变，记录失败和下次重试时间。
     */
    @Update(
        "UPDATE group_memory_state SET status = 'IDLE', claim_token = NULL, "
            + "attempt_count = attempt_count + 1, next_attempt_at = #{nextAttemptAt}, last_error = #{error}, "
            + "updated_at = #{now} "
            + "WHERE group_id = #{groupId} AND status = 'RUNNING' AND claim_token = #{claimToken}")
    int recordFailure(
        @Param("groupId") Long groupId,
        @Param("claimToken") LocalDateTime claimToken,
        @Param("error") String error,
        @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
        @Param("now") LocalDateTime now);

    /**
     * 项目记忆发现的到期群：只取项目默认群。受限群内容不进模型、不发建议消息、不写入项目记忆，
     * 因此这里先用 SQL 排除，不依赖提示词或后续校验。连续失败达到上限的群等待群记忆重新提炼后再试。
     */
    @Select(
        "SELECT g.* FROM group_memory_state g JOIN chat_groups cg ON cg.id = g.group_id "
            + "WHERE cg.default_group = 1 AND g.discovery_attempt_count < #{maxAttempts} "
            + "AND (g.discovery_next_attempt_at IS NULL OR g.discovery_next_attempt_at <= #{now}) "
            + "ORDER BY g.group_id LIMIT #{limit}")
    List<GroupMemoryStateEntity> selectDiscoveryCandidates(
        @Param("now") LocalDateTime now,
        @Param("maxAttempts") int maxAttempts,
        @Param("limit") int limit);

    /**
     * 领取一轮项目记忆发现并把下次发现时间推到 nextSlot；返回 0 说明其他任务已领取或未到周期。
     */
    @Update(
        "UPDATE group_memory_state SET discovery_next_attempt_at = #{nextSlot}, updated_at = #{now} "
            + "WHERE group_id = #{groupId} "
            + "AND (discovery_next_attempt_at IS NULL OR discovery_next_attempt_at <= #{now})")
    int claimDiscovery(
        @Param("groupId") Long groupId,
        @Param("nextSlot") LocalDateTime nextSlot,
        @Param("now") LocalDateTime now);

    /**
     * 发现完成（含“没有建议”）：记录已处理的群记忆版本，清零失败次数；同一版本不再重复发现。
     */
    @Update(
        "UPDATE group_memory_state SET discovered_version = #{version}, discovery_attempt_count = 0, "
            + "discovery_next_attempt_at = #{nextSlot}, discovery_error = NULL, updated_at = #{now} "
            + "WHERE group_id = #{groupId}")
    int finishDiscovery(
        @Param("groupId") Long groupId,
        @Param("version") String version,
        @Param("nextSlot") LocalDateTime nextSlot,
        @Param("now") LocalDateTime now);

    /**
     * 发现失败：不推进 discovered_version，记录失败摘要并安排重试；达到上限后等群记忆更新。
     */
    @Update(
        "UPDATE group_memory_state SET discovery_attempt_count = discovery_attempt_count + 1, "
            + "discovery_next_attempt_at = #{nextAttemptAt}, discovery_error = #{error}, updated_at = #{now} "
            + "WHERE group_id = #{groupId}")
    int recordDiscoveryFailure(
        @Param("groupId") Long groupId,
        @Param("error") String error,
        @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
        @Param("now") LocalDateTime now);
}
