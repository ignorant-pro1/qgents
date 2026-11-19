package qg.qgent.memory.integration;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import qg.qgent.chat.event.GroupMessageCreatedEvent;
import qg.qgent.memory.mapper.GroupMemoryStateMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 消息事件维护群记忆的活跃状态；与消息同一事务，消息回滚时不会留下待提炼状态。
 */
@Component
public class GroupMemoryActivityListener {

    private final GroupMemoryStateMapper states;

    public GroupMemoryActivityListener(GroupMemoryStateMapper states) {
        this.states = states;
    }

    @EventListener
    public void onMessageCreated(GroupMessageCreatedEvent event) {
        states.recordActivity(
            event.projectId(), event.groupId(), LocalDateTime.now(ZoneOffset.UTC));
    }
}
