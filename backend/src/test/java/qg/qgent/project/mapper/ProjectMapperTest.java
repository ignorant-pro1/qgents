package qg.qgent.project.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qg.qgent.project.entity.ProjectEntity;
import qg.qgent.support.TestIds;

class ProjectMapperTest {
    @Test
    void lockingQueriesLoadAvatarAndNullAvatarIsIncludedInGeneratedUpdate() {
        var configuration = new MybatisConfiguration();
        configuration.addMapper(ProjectMapper.class);
        String namespace = ProjectMapper.class.getName();
        var statement = configuration.getMappedStatement(namespace + ".selectByIdForUpdate");
        assertThat(statement.getBoundSql(Map.of("projectId", TestIds.next())).getSql())
                .contains("avatar_url");
        assertThat(statement.getResultMaps().getFirst().getResultMappings())
                .anyMatch(field -> "avatarUrl".equals(field.getProperty()));
        ProjectEntity project = new ProjectEntity();
        project.setId(TestIds.next());
        project.setName("项目");
        var sql =
                configuration
                        .getMappedStatement(namespace + ".updateById")
                        .getBoundSql(Map.of("et", project));
        assertThat(sql.getSql()).contains("avatar_url=?");
        assertThat(sql.getParameterMappings())
                .anyMatch(parameter -> "et.avatarUrl".equals(parameter.getProperty()));
    }
}
