package qg.qgent.agent.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import qg.qgent.support.TestIds;

/**
 * Agent 申请 SQL 在真实 MyBatis 参数绑定下执行，只把 JDBC 连接换成替身：
 * 参数名或状态条件写错的语句会在这里失败，而不是等到生产审批才暴露。
 */
class AgentMapperTest {

    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final PreparedStatement statement = mock(PreparedStatement.class);
    private MybatisConfiguration configuration;
    private SqlSessionFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(connection.prepareStatement(anyString(), anyInt(), anyInt())).thenReturn(statement);
        when(statement.execute()).thenReturn(true);
        when(statement.getUpdateCount()).thenReturn(1);
        configuration = new MybatisConfiguration();
        configuration.addMapper(AgentMapper.class);
        configuration.setEnvironment(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
        factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @Test
    void activationOnlyAppliesToApplicationsStillPending() throws Exception {
        Long id = TestIds.next(), approver = TestIds.next();

        try (SqlSession session = factory.openSession()) {
            assertThat(session.getMapper(AgentMapper.class).activate(id, approver)).isEqualTo(1);
        }

        assertThat(updateSql())
                .contains(
                        "SET status = 'ACTIVE'", "approved_by = ?", "id = ?", "status = 'PENDING'")
                .contains("approved_at = UTC_TIMESTAMP(6)");
    }

    @Test
    void rejectionOnlyAppliesToApplicationsStillPending() throws Exception {
        Long id = TestIds.next(), approver = TestIds.next();

        try (SqlSession session = factory.openSession()) {
            assertThat(session.getMapper(AgentMapper.class).reject(id, approver)).isEqualTo(1);
        }

        assertThat(updateSql())
                .contains("SET status = 'REJECTED'", "approved_by = ?", "status = 'PENDING'");
    }

    @Test
    void onlyActiveAgentsAreListedForInvitesAndPendingOnesForApproval() {
        assertThat(selectSql("selectActiveByProject", Map.of("projectId", TestIds.next())))
                .contains("status = 'ACTIVE'");
        assertThat(selectSql("selectPendingByProject", Map.of("projectId", TestIds.next())))
                .contains("status = 'PENDING'");
    }

    @Test
    void theLimitCountIsALockingReadSoConcurrentApplicationsSeeCommittedRows() {
        // 事务内先做了普通读（成员校验）才拿到项目行锁，快照可能落后；计数必须是加锁读才能看到刚提交的申请。
        assertThat(selectSql("countCountedByProject", Map.of("projectId", TestIds.next())))
                .contains("status <> 'REJECTED'", "FOR UPDATE");
    }

    @Test
    void everyStatementParameterResolvesToADeclaredMethodParameter() {
        assertParametersResolve("selectDefaultAgent", Map.of("projectId", TestIds.next()));
        assertParametersResolve("selectActiveByProject", Map.of("projectId", TestIds.next()));
        assertParametersResolve("selectPendingByProject", Map.of("projectId", TestIds.next()));
        assertParametersResolve("countCountedByProject", Map.of("projectId", TestIds.next()));
        assertParametersResolve(
                "activate", Map.of("id", TestIds.next(), "approvedBy", TestIds.next()));
        assertParametersResolve(
                "reject", Map.of("id", TestIds.next(), "approvedBy", TestIds.next()));
    }

    private void assertParametersResolve(String method, Map<String, Object> declaredParameters) {
        var boundSql = statement(method).getBoundSql(declaredParameters);
        assertThat(boundSql.getParameterMappings())
                .isNotEmpty()
                .allSatisfy(mapping ->
                        assertThat(declaredParameters)
                                .as("参数 %s 必须对应方法上的 @Param", mapping.getProperty())
                                .containsKey(mapping.getProperty()));
    }

    private String selectSql(String method, Map<String, Object> declaredParameters) {
        return statement(method).getBoundSql(declaredParameters).getSql();
    }

    private org.apache.ibatis.mapping.MappedStatement statement(String method) {
        return configuration.getMappedStatement(AgentMapper.class.getName() + "." + method);
    }

    private String updateSql() throws Exception {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        return sql.getValue();
    }
}
