package qg.qgent.harness.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;

class AgentRunControllerRouteTest {
    @Test
    void exposesGroupAgentStreamAndSnapshotRoutes() {
        Set<String> routes = new HashSet<>();
        for (Method method : AgentRunController.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(GetMapping.class)) {
                routes.add("GET " + method.getAnnotation(GetMapping.class).value()[0]);
            }
        }

        assertEquals(
                Set.of(
                        "GET /api/v1/projects/{projectId}/groups/{groupId}/agent-events",
                        "GET /api/v1/projects/{projectId}/agent-runs"),
                routes);
    }
}
