package qg.qgent.memory.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qg.qgent.support.TestIds;

/** 群记忆提炼状态的 SQL 约束：失败不改水位、完成需持有领取、纠错累积。 */
class GroupMemoryStateMapperTest {

    private final MybatisConfiguration configuration = new MybatisConfiguration();

    GroupMemoryStateMapperTest() {
        configuration.addMapper(GroupMemoryStateMapper.class);
    }

    @Test
    void failureKeepsProcessedSequenceAndCorrection() {
        String sql =
                sql(
                        "recordFailure",
                        Map.of(
                                "groupId",
                                TestIds.next(),
                                "claimToken",
                                LocalDateTime.now(),
                                "error",
                                "超时",
                                "nextAttemptAt",
                                LocalDateTime.now(),
                                "now",
                                LocalDateTime.now()));

        assertThat(sql).doesNotContain("last_processed_sequence").doesNotContain("correction =");
    }

    @Test
    void claimTakesIdleOrStaleRowsAndWritesClaimToken() {
        String sql =
                sql(
                        "claim",
                        Map.of(
                                "groupId",
                                TestIds.next(),
                                "now",
                                LocalDateTime.now(),
                                "staleBefore",
                                LocalDateTime.now()));

        assertThat(sql)
                .contains("status = 'IDLE'")
                .contains("updated_at < ")
                .contains("claim_token = ");
    }

    @Test
    void resultUpdatesRequireTheClaimTokenStillBeingOwned() {
        String finish = sql("finishSuccess", successParameters());
        String failure =
                sql(
                        "recordFailure",
                        Map.of(
                                "groupId",
                                TestIds.next(),
                                "claimToken",
                                LocalDateTime.now(),
                                "error",
                                "超时",
                                "nextAttemptAt",
                                LocalDateTime.now(),
                                "now",
                                LocalDateTime.now()));

        assertThat(finish).contains("status = 'RUNNING'", "claim_token = ?");
        assertThat(failure).contains("status = 'RUNNING'", "claim_token = ?");
    }

    @Test
    void successNeverMovesTheWatermarkBackwards() {
        String sql = sql("finishSuccess", successParameters());

        assertThat(sql).contains("GREATEST(last_processed_sequence");
    }

    @Test
    void successOnlyClearsTheCorrectionItActuallyApplied() {
        String sql = sql("finishSuccess", successParameters());

        assertThat(sql).contains("CASE WHEN correction = ").contains("ELSE correction END");
    }

    @Test
    void correctionsAccumulateInsteadOfOverwritingEachOther() {
        String sql =
                sql(
                        "requestCorrection",
                        Map.of(
                                "projectId",
                                TestIds.next(),
                                "groupId",
                                TestIds.next(),
                                "correction",
                                "结论应为 B",
                                "at",
                                LocalDateTime.now()));

        assertThat(sql).contains("CONCAT(correction", "CHAR(10)");
    }

    @Test
    void candidateScanPagesByGroupIdCursorSoLaterGroupsAreReached() {
        String sql =
                sql(
                        "selectCandidates",
                        Map.of(
                                "staleBefore",
                                LocalDateTime.now(),
                                "afterGroupId",
                                TestIds.next(),
                                "limit",
                                20));

        assertThat(sql).contains("group_id > ?").contains("ORDER BY group_id");
    }

    @Test
    void discoveryOnlyScansDueProjectDefaultGroupsWithinTheRetryBudget() {
        String sql =
                sql(
                        "selectDiscoveryCandidates",
                        Map.of("now", LocalDateTime.now(), "maxAttempts", 3, "limit", 10));

        // 受限群的群记忆不是发现来源：连模型都不该看到，所以由 SQL 先排除
        assertThat(sql)
                .contains("default_group = 1")
                .contains("discovery_next_attempt_at <= ?")
                .contains("discovery_attempt_count < ?")
                .contains("ORDER BY g.group_id");
    }

    @Test
    void groupMemoryUpdateGivesDiscoveryAFreshRetryBudget() {
        String sql = sql("finishSuccess", successParameters());

        assertThat(sql).contains("discovery_attempt_count = 0");
    }

    @Test
    void discoveryClaimOnlyTakesTheDueSlot() {
        String sql =
                sql(
                        "claimDiscovery",
                        Map.of(
                                "groupId",
                                TestIds.next(),
                                "nextSlot",
                                LocalDateTime.now(),
                                "now",
                                LocalDateTime.now()));

        assertThat(sql)
                .contains("discovery_next_attempt_at = ?")
                .contains("discovery_next_attempt_at IS NULL OR discovery_next_attempt_at <= ?");
    }

    @Test
    void discoveryFailureKeepsTheDiscoveredVersionAndSchedulesARetry() {
        String sql =
                sql(
                        "recordDiscoveryFailure",
                        Map.of(
                                "groupId",
                                TestIds.next(),
                                "error",
                                "模型调用超时",
                                "nextAttemptAt",
                                LocalDateTime.now(),
                                "now",
                                LocalDateTime.now()));

        assertThat(sql)
                .doesNotContain("discovered_version")
                .contains("discovery_attempt_count = discovery_attempt_count + 1")
                .contains("discovery_next_attempt_at = ?");
    }

    @Test
    void discoverySuccessRecordsTheVersionAndClearsTheError() {
        String sql =
                sql(
                        "finishDiscovery",
                        Map.of(
                                "groupId",
                                TestIds.next(),
                                "version",
                                "v2",
                                "nextSlot",
                                LocalDateTime.now(),
                                "now",
                                LocalDateTime.now()));

        assertThat(sql)
                .contains(
                        "discovered_version = ?",
                        "discovery_attempt_count = 0",
                        "discovery_error = NULL");
    }

    @Test
    void firstCandidatePageHasNoCursorCondition() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("staleBefore", LocalDateTime.now());
        parameters.put("afterGroupId", null);
        parameters.put("limit", 20);

        assertThat(sql("selectCandidates", parameters)).doesNotContain("group_id >");
    }

    private Map<String, Object> successParameters() {
        return Map.of(
                "groupId",
                TestIds.next(),
                "claimToken",
                LocalDateTime.now(),
                "appliedCorrection",
                "结论应为 B",
                "processedThrough",
                7L,
                "now",
                LocalDateTime.now());
    }

    private String sql(String method, Map<String, Object> parameters) {
        return configuration
                .getMappedStatement(GroupMemoryStateMapper.class.getName() + "." + method)
                .getBoundSql(parameters)
                .getSql();
    }
}
