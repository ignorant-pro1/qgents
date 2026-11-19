package qg.qgent.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 公开接口文档只做路径过滤和分组，不再按 URL 猜测摘要。 */
class FrontendOpenApiConfigurationTest {

    @Test
    void keepsOnlyPublicPathsAndGroupsThemWithoutInventingSummaries() {
        OpenAPI openApi =
                new OpenAPI()
                        .paths(
                                new Paths()
                                        .addPathItem(
                                                "/api/v1/projects/{projectId}/groups/{groupId}/messages",
                                                new PathItem().post(new Operation()))
                                        .addPathItem(
                                                "/api/v1/teams/{teamId}",
                                                new PathItem().get(new Operation().summary("查询团队")))
                                        .addPathItem(
                                                "/internal/metrics",
                                                new PathItem().get(new Operation())));

        new FrontendOpenApiConfiguration().frontendApiChineseDocumentation().customise(openApi);

        assertEquals(
                List.of(
                        "/api/v1/projects/{projectId}/groups/{groupId}/messages",
                        "/api/v1/teams/{teamId}"),
                List.copyOf(openApi.getPaths().keySet()));
        Operation sendMessage =
                openApi.getPaths()
                        .get("/api/v1/projects/{projectId}/groups/{groupId}/messages")
                        .getPost();
        assertEquals(List.of("群组与消息"), sendMessage.getTags());
        assertNull(sendMessage.getSummary());
        assertEquals(
                List.of("团队与邀请"),
                openApi.getPaths().get("/api/v1/teams/{teamId}").getGet().getTags());
        assertEquals(
                "查询团队", openApi.getPaths().get("/api/v1/teams/{teamId}").getGet().getSummary());
    }
}
