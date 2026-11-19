package qg.qgent.skill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import org.apache.ibatis.annotations.*;
import qg.qgent.skill.dto.ContextSkill;
import qg.qgent.skill.entity.SkillEntity;

import java.util.List;

/**
 * Skill 数据访问。
 */
@Mapper
public interface SkillMapper extends BaseMapper<SkillEntity> {

    /**
     * 查询自己的 Skill、已发布共享内容；管理员可查看所有共享状态。
     */
    @Select({
        "<script>",
        "SELECT * FROM skills WHERE project_id = #{projectId}",
        "AND (created_by = #{actor} OR (visibility = 'PROJECT_SHARED' AND (status = 'PUBLISHED' OR #{admin})))",
        "<if test='status != null'>AND status = #{status}</if>",
        "<if test='tag != null'>AND JSON_CONTAINS(tags, JSON_QUOTE(#{tag}))</if>",
        "ORDER BY updated_at DESC",
        "</script>"
    })
    @Results(
        id = "skill",
        value = {
            @Result(column = "id", property = "id", id = true),
            @Result(column = "project_id", property = "projectId"),
            @Result(column = "created_by", property = "createdBy"),
            @Result(column = "submitted_by", property = "submittedBy"),
            @Result(column = "reviewer_id", property = "reviewerId"),
            @Result(column = "tags", property = "tags", typeHandler = JacksonTypeHandler.class)
        })
    List<SkillEntity> listSkills(
        @Param("projectId") Long projectId,
        @Param("actor") Long actor,
        @Param("status") String status,
        @Param("tag") String tag,
        @Param("admin") boolean admin);

    /**
     * 查询默认上下文可见的已发布 Skill 目录，不读取正文。
     */
    @Select(
        "SELECT id, name, description FROM skills WHERE project_id = #{projectId} "
            + "AND (visibility = 'PROJECT_SHARED' OR created_by = #{actor}) "
            + "AND status = 'PUBLISHED' ORDER BY updated_at DESC")
    @Results({
        @Result(column = "id", property = "id", id = true),
        @Result(column = "name", property = "name"),
        @Result(column = "description", property = "description")
    })
    List<ContextSkill> listPublishedCatalog(
        @Param("projectId") Long projectId, @Param("actor") Long actor);

    /**
     * 读取一条当前用户可见的已发布 Skill 正文，供显式激活使用。
     */
    @Select(
        "SELECT * FROM skills WHERE id = #{skillId} AND project_id = #{projectId} "
            + "AND (visibility = 'PROJECT_SHARED' OR created_by = #{actor}) "
            + "AND status = 'PUBLISHED'")
    @ResultMap("skill")
    SkillEntity findVisiblePublishedById(
        @Param("projectId") Long projectId,
        @Param("actor") Long actor,
        @Param("skillId") Long skillId);
}
