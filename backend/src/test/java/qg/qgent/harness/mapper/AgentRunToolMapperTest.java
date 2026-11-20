package qg.qgent.harness.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qg.qgent.support.TestIds;

class AgentRunToolMapperTest {
    @Test
    void settlesAToolByRunAndCallId() {
        var configuration = new MybatisConfiguration();
        configuration.addMapper(AgentRunToolMapper.class);
        var sql =
                configuration
                        .getMappedStatement(AgentRunToolMapper.class.getName() + ".finish")
                        .getBoundSql(
                                Map.of(
                                        "runId",
                                        TestIds.next(),
                                        "toolCallId",
                                        "call-1",
                                        "state",
                                        "ok",
                                        "output",
                                        "{\"ok\":true}",
                                        "finishedAt",
                                        LocalDateTime.now()))
                        .getSql();
        assertThat(sql)
                .contains("SET state = ?, output = ?, finished_at = ?")
                .contains("WHERE run_id = ? AND tool_call_id = ?");
    }

    @Test
    void loadsAllToolsOfSeveralRunsInOneQuery() {
        var configuration = new MybatisConfiguration();
        configuration.addMapper(AgentRunToolMapper.class);
        var sql =
                configuration
                        .getMappedStatement(AgentRunToolMapper.class.getName() + ".byRunIds")
                        .getBoundSql(Map.of("runIds", List.of(TestIds.next(), TestIds.next())))
                        .getSql();
        assertThat(sql).matches(
            "(?s)SELECT .* WHERE run_id IN\\s*\\(\\s*\\?\\s*,\\s*\\?\\s*\\)\\s*ORDER BY id");
    }
}
