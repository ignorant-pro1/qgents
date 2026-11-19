package qg.qgent.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import qg.qgent.chat.entity.ChatGroupEntity;

import java.util.List;

@Mapper
public interface ChatGroupMapper extends BaseMapper<ChatGroupEntity> {
    @Select("SELECT user_id FROM group_users WHERE group_id = #{groupId}")
    List<Long> selectUserIds(Long groupId);

    @Select(
        "select * from chat_groups where project_id=#{projectId} ORDER BY default_group DESC, created_at ASC")
    List<ChatGroupEntity> listByProject(Long projectId);
}
