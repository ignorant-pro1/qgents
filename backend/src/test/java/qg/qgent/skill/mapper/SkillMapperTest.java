package qg.qgent.skill.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qg.qgent.support.TestIds;

class SkillMapperTest {
    @Test
    void listAndActivationShareMappingsAndListRestrictsUnpublishedSharedContent() {
        var configuration = new MybatisConfiguration();
        configuration.addMapper(SkillMapper.class);
        String namespace = SkillMapper.class.getName();
        var list = configuration.getMappedStatement(namespace + ".listSkills");
        var activation = configuration.getMappedStatement(namespace + ".findVisiblePublishedById");
        assertThat(activation.getResultMaps().getFirst()).isSameAs(list.getResultMaps().getFirst());
        assertThat(list.getResultMaps().getFirst().getResultMappings()).hasSize(6);
        var sql =
                list.getBoundSql(
                        Map.of(
                                "projectId",
                                TestIds.next(),
                                "actor",
                                TestIds.next(),
                                "admin",
                                false));
        assertThat(sql.getSql())
                .contains(
                        "created_by = ? OR (visibility = 'PROJECT_SHARED' AND (status = 'PUBLISHED' OR ?))");
    }
}
