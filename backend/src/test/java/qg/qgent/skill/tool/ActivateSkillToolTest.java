package qg.qgent.skill.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import qg.qgent.skill.entity.SkillEntity;
import qg.qgent.skill.service.SkillService;
import qg.qgent.support.TestIds;

class ActivateSkillToolTest {

    private final SkillService contextService = mock(SkillService.class);
    private final Long actor = TestIds.next();
    private final Long projectId = TestIds.next();

    @Test
    void exposesNativeToolAndReturnsOnlyExplicitlyActivatedBody() {
        Long skillId = TestIds.next();
        SkillEntity skill = new SkillEntity();
        skill.setName("database-migration");
        skill.setContent("完整迁移规范");
        when(contextService.activateSkill(actor, projectId, skillId)).thenReturn(skill);

        ActivateSkillTool tool = new ActivateSkillTool(contextService, actor, projectId);
        Map<String, Object> result = tool.activateSkill(skillId.toString());

        assertThat(ToolCallbacks.from(tool)[0].getToolDefinition().name())
                .isEqualTo("activate_skill");
        assertThat(result)
                .containsEntry("ok", true)
                .containsEntry("name", "database-migration")
                .containsEntry("content", "完整迁移规范")
                .containsEntry("budget", "used 1/5");
    }

    @Test
    void repeatedActivationDoesNotUseAnotherBudgetOrReadAgain() {
        Long skillId = TestIds.next();
        SkillEntity skill = new SkillEntity();
        skill.setName("migration");
        skill.setContent("body");
        when(contextService.activateSkill(actor, projectId, skillId)).thenReturn(skill);
        ActivateSkillTool tool = new ActivateSkillTool(contextService, actor, projectId);

        tool.activateSkill(skillId.toString());
        Map<String, Object> repeated = tool.activateSkill(skillId.toString());

        assertThat(repeated)
                .containsEntry("ok", true)
                .containsEntry("alreadyActivated", true)
                .containsEntry("budget", "used 1/5")
                .doesNotContainKey("content");
        verify(contextService, times(1)).activateSkill(actor, projectId, skillId);
    }

    @Test
    void sixthDifferentSkillIsRejectedWithoutServiceCall() {
        ActivateSkillTool tool = new ActivateSkillTool(contextService, actor, projectId);
        for (int index = 0; index < 5; index++) {
            Long skillId = TestIds.next();
            SkillEntity skill = new SkillEntity();
            skill.setName("skill-" + index);
            when(contextService.activateSkill(actor, projectId, skillId)).thenReturn(skill);
            assertThat(tool.activateSkill(skillId.toString())).containsEntry("ok", true);
        }

        assertThat(tool.activateSkill(Long.toString(TestIds.next())))
                .containsEntry("ok", false)
                .containsEntry("error", "activate_skill 激活预算已用尽（已激活 5/5 个不同 Skill），请基于当前上下文继续");
        verify(contextService, times(5)).activateSkill(eq(actor), eq(projectId), any());
    }
}
