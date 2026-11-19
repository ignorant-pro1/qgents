package qg.qgent.chat.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import qg.qgent.chat.mapper.view.GroupAgentRow;

import java.util.List;

/**
 * 群内 Agent 成员；Agent 入群后自动接受邀请，无需额外状态。
 */
@Mapper
public interface GroupAgentMapper {

    @Insert("INSERT IGNORE INTO group_agents(group_id, agent_id) VALUES(#{groupId}, #{agentId})")
    int insertAgent(@Param("groupId") Long groupId, @Param("agentId") Long agentId);

    @Select(
        "SELECT a.id AS agent_id, a.name, a.avatar_url FROM group_agents ga JOIN agents a ON a.id = ga.agent_id "
            + "WHERE ga.group_id = #{groupId} ORDER BY a.name")
    List<GroupAgentRow> selectAgents(@Param("groupId") Long groupId);

    @Select("SELECT agent_id FROM group_agents WHERE group_id = #{groupId}")
    List<Long> selectAgentIds(@Param("groupId") Long groupId);

    @Select("SELECT group_id FROM group_agents WHERE agent_id = #{agentId}")
    List<Long> selectGroupIds(@Param("agentId") Long agentId);

    @Select("SELECT COUNT(*) FROM group_agents WHERE group_id = #{groupId}")
    Long countAgents(@Param("groupId") Long groupId);
}
