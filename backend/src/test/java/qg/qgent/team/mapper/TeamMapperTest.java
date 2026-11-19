package qg.qgent.team.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qg.qgent.support.TestIds;
import qg.qgent.team.entity.TeamEntity;

class TeamMapperTest {
    @Test
    void clearingAvatarIncludesNullInDatabaseUpdate() {
        var configuration = configuration();
        TeamEntity team = new TeamEntity();
        team.setId(TestIds.next());
        team.setName("团队");
        var sql =
                configuration
                        .getMappedStatement(TeamMapper.class.getName() + ".updateById")
                        .getBoundSql(Map.of("et", team));
        assertThat(sql.getSql()).contains("avatar_url=?");
        assertThat(sql.getParameterMappings())
                .anyMatch(field -> "et.avatarUrl".equals(field.getProperty()));
    }

    @Test
    void teamListsAndMemberQueriesKeepUuidAndProfileMappings() {
        var configuration = configuration();
        configuration.addMapper(TeamMemberMapper.class);
        for (String method : new String[] {"selectMembershipPage"}) {
            var mappings =
                    configuration
                            .getMappedStatement(TeamMapper.class.getName() + "." + method)
                            .getResultMaps()
                            .getFirst()
                            .getResultMappings();
            assertThat(mappings).anyMatch(field -> "id".equals(field.getProperty()));
            assertThat(mappings).anyMatch(field -> "avatarUrl".equals(field.getProperty()));
            assertThat(mappings).anyMatch(field -> "memberCount".equals(field.getProperty()));
        }
        for (String method :
                new String[] {"selectByTeamAndUser", "selectByTeamId", "selectByUserId"}) {
            var mappings =
                    configuration
                            .getMappedStatement(TeamMemberMapper.class.getName() + "." + method)
                            .getResultMaps()
                            .getFirst()
                            .getResultMappings();
            assertThat(mappings).anyMatch(field -> "userId".equals(field.getProperty()));
        }
    }

    private MybatisConfiguration configuration() {
        var configuration = new MybatisConfiguration();
        configuration.addMapper(TeamMapper.class);
        return configuration;
    }
}
