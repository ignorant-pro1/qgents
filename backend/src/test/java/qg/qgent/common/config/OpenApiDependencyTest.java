package qg.qgent.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class OpenApiDependencyTest {
    @Test
    void schemaAnnotationsHaveOneCompatibleClasspathDefinition() throws Exception {
        var definitions =
                Collections.list(
                        Schema.class
                                .getClassLoader()
                                .getResources("io/swagger/v3/oas/annotations/media/Schema.class"));
        assertEquals(1, definitions.size(), "不能同时加载 OpenAI SDK 和 Jakarta 的同名 Swagger 注解");
        assertNotNull(Schema.class.getMethod("$dynamicRef"));
    }
}
