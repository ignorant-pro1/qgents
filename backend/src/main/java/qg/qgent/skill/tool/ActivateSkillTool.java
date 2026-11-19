package qg.qgent.skill.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import qg.qgent.harness.tool.ToolResult;
import qg.qgent.skill.entity.SkillEntity;
import qg.qgent.skill.service.SkillService;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 每次 AgentRun 按需读取完整 Skill 正文；已激活集合仅在本次运行内有效。
 */
@Slf4j
public class ActivateSkillTool {

    private static final int MAX_ACTIVATIONS = 5;

    private final SkillService skills;
    private final Long actor;
    private final Long projectId;
    private final Set<Long> activatedSkillIds = new LinkedHashSet<>();

    public ActivateSkillTool(SkillService skills, Long actor, Long projectId) {
        this.skills = skills;
        this.actor = actor;
        this.projectId = projectId;
    }

    /**
     * 读取已发布且当前用户可见的项目 Skill。
     */
    @Tool(
        name = "activate_skill",
        description =
            "按 Skill 目录中的 skillId 激活完整 Skill 正文；"
                + "仅在当前上下文确实需要该操作规范时调用。每个 AgentRun 最多激活 5 个不同 Skill，"
                + "重复激活不会重复消耗预算")
    public ToolResult activateSkill(
        @ToolParam(description = "默认上下文 Skill 目录中的数字 skillId") String skillId) {
        Long parsedSkillId;
        try {
            parsedSkillId = Long.valueOf(skillId == null ? "" : skillId.trim());
        } catch (IllegalArgumentException exception) {
            return ToolResult.error(
                "INVALID_ARGUMENT", "activate_skill 的 skillId 必须是 Skill 目录中的有效 Long");
        }
        if (activatedSkillIds.contains(parsedSkillId)) {
            ToolResult result = ToolResult.ok();
            result.put("alreadyActivated", true);
            result.put("skillId", parsedSkillId.toString());
            result.put("budget", "used " + activatedSkillIds.size() + "/" + MAX_ACTIVATIONS);
            log.info(
                "skill activation repeated projectId={} skillId={} used={}",
                projectId,
                parsedSkillId,
                activatedSkillIds.size());
            return result;
        }
        if (activatedSkillIds.size() >= MAX_ACTIVATIONS) {
            return ToolResult.error(
                "SKILL_ACTIVATION_LIMIT",
                "activate_skill 激活预算已用尽（已激活 "
                    + activatedSkillIds.size()
                    + "/"
                    + MAX_ACTIVATIONS
                    + " 个不同 Skill），请基于当前上下文继续");
        }
        final SkillEntity skill;
        try {
            skill = skills.activateSkill(actor, projectId, parsedSkillId);
        } catch (RuntimeException exception) {
            log.info(
                "skill activation denied projectId={} skillId={} category={}",
                projectId,
                parsedSkillId,
                exception.getClass().getSimpleName());
            return ToolResult.error(
                "SKILL_UNAVAILABLE", "activate_skill 失败: " + safeMessage(exception));
        }
        activatedSkillIds.add(parsedSkillId);
        ToolResult result = ToolResult.ok();
        result.put("skillId", parsedSkillId.toString());
        result.put("name", skill.getName());
        result.put("content", skill.getContent());
        result.put("budget", "used " + activatedSkillIds.size() + "/" + MAX_ACTIVATIONS);
        log.info(
            "skill activated projectId={} skillId={} used={}",
            projectId,
            parsedSkillId,
            activatedSkillIds.size());
        return result;
    }

    private String safeMessage(Throwable throwable) {
        if (throwable == null
            || throwable.getMessage() == null
            || throwable.getMessage().isBlank()) {
            return "skill unavailable";
        }
        String firstLine =
            throwable.getMessage().strip().lines().findFirst().orElse("skill unavailable");
        return firstLine.length() <= 200 ? firstLine : firstLine.substring(0, 200);
    }
}
