package qg.qgent.realtime.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import qg.qgent.chat.mapper.ChatGroupMapper;
import qg.qgent.common.config.PerformanceMetrics;
import qg.qgent.notification.mapper.NotificationEventMapper;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.mapper.ProjectMemberMapper;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.realtime.mapper.EventMapper;
import qg.qgent.realtime.service.EventService;
import qg.qgent.realtime.service.RealtimeHub;
import qg.qgent.realtime.transport.SseEventTransport;
import qg.qgent.realtime.websocket.WebSocketRealtimeHandler;
import qg.qgent.team.mapper.TeamEventMapper;
import qg.qgent.team.mapper.TeamMapper;
import qg.qgent.team.mapper.TeamMemberMapper;
import qg.qgent.user.mapper.UserMapper;

class RealtimeTransportWiringTest {

    @Test
    void publishesThroughTheHubWithoutCouplingTheSsePumpToTheWebSocketHandler() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext()) {
            context.registerBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(Clock.class, Clock::systemUTC);
            registerMock(context, EventMapper.class);
            registerMock(context, ProjectAccessService.class);
            registerMock(context, NotificationEventMapper.class);
            registerMock(context, TeamEventMapper.class);
            registerMock(context, ProjectMemberMapper.class);
            registerMock(context, TeamMemberMapper.class);
            registerMock(context, ChatGroupMapper.class);
            registerMock(context, ProjectMapper.class);
            registerMock(context, UserMapper.class);
            registerMock(context, TeamMapper.class);
            registerMock(context, PlatformTransactionManager.class);
            context.register(
                    PerformanceMetrics.class,
                    SseTransportConfiguration.class,
                    RealtimeHub.class,
                    WebSocketRealtimeHandler.class,
                    EventService.class,
                    SseEventTransport.class);
            context.refresh();

            assertNotNull(context.getBean(EventService.class));
            assertNotNull(context.getBean(RealtimeHub.class));
            assertNotNull(context.getBean(SseEventTransport.class));
            assertNotNull(context.getBean(WebSocketRealtimeHandler.class));
        }
    }

    private static <T> void registerMock(
            AnnotationConfigApplicationContext context, Class<T> type) {
        context.registerBean(type, () -> mock(type));
    }
}
