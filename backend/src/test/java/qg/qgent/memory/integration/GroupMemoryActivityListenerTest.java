package qg.qgent.memory.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import qg.qgent.chat.event.GroupMessageCreatedEvent;
import qg.qgent.memory.mapper.GroupMemoryStateMapper;
import qg.qgent.support.TestIds;

/** 消息事件把群的活跃时间写进提炼状态，定时扫描据此判断闲置时长。 */
class GroupMemoryActivityListenerTest {

    private final GroupMemoryStateMapper states = mock(GroupMemoryStateMapper.class);
    private final GroupMemoryActivityListener listener = new GroupMemoryActivityListener(states);

    @Test
    void recordsActivityForEveryCommittedMessage() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();

        listener.onMessageCreated(new GroupMessageCreatedEvent(projectId, groupId));

        verify(states).recordActivity(eq(projectId), eq(groupId), any());
    }
}
