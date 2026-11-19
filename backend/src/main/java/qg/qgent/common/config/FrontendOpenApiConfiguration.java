package qg.qgent.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 为公开业务接口补充中文分组，保留 Controller 的接口描述。
 */
@Configuration
public class FrontendOpenApiConfiguration {

    @Bean
    OpenApiCustomizer frontendApiChineseDocumentation() {
        return this::documentPublicPaths;
    }

    private void documentPublicPaths(OpenAPI openApi) {
        if (openApi.getPaths() == null) {
            return;
        }
        openApi.getPaths().keySet().removeIf(path -> !path.startsWith("/api/v1/"));
        openApi.getPaths()
            .forEach((path, item) ->
                item.readOperationsMap()
                    .values()
                    .forEach(operation -> operation.setTags(List.of(group(path)))));
        openApi.setTags(
            List.of(
                    "认证与账户",
                    "团队与邀请",
                    "项目与项目成员",
                    "GitHub App 与仓库导入",
                    "群组与消息",
                    "通知中心",
                    "共享 Skill",
                    "共享 Memory",
                    "实时事件流")
                .stream()
                .map(name -> new Tag().name(name))
                .toList());
    }

    private String group(String path) {
        if (path.contains("/integrations/github") || path.contains("/repositories"))
            return "GitHub App 与仓库导入";
        if (path.contains("/events")) return "实时事件流";
        if (path.startsWith("/api/v1/notifications")) return "通知中心";
        if (path.contains("/groups") || path.contains("/messages") || path.contains("/attachments"))
            return "群组与消息";
        if (path.contains("/skills")) return "共享 Skill";
        if (path.contains("/memories")) return "共享 Memory";
        if (path.contains("/auth/") || path.equals("/api/v1/me") || path.startsWith("/api/v1/me/"))
            return "认证与账户";
        if (path.contains("/projects")) return "项目与项目成员";
        return "团队与邀请";
    }
}
