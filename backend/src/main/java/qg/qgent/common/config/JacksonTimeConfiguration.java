package qg.qgent.common.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 将项目约定为 UTC 的 LocalDateTime 序列化为带 Z 后缀的 ISO8601 字符串，避免客户端按本地时区解析。
 */
@Configuration
public class JacksonTimeConfiguration {

    /**
     * LocalDateTime → 带 Z 的 UTC ISO8601（如 {@code 2026-08-18T15:26:31.990054Z}）。
     * SecurityConfiguration 的全局 ObjectMapper 注册本模块，所有接口响应统一生效。
     */
    @Bean
    SimpleModule utcLocalDateTimeModule() {
        SimpleModule module = new SimpleModule("qgents-utc-localdatetime");
        module.addSerializer(
            LocalDateTime.class,
            new JsonSerializer<LocalDateTime>() {
                @Override
                public void serialize(
                    LocalDateTime value,
                    JsonGenerator generator,
                    SerializerProvider serializers)
                    throws IOException {
                    generator.writeString(value.toInstant(ZoneOffset.UTC).toString());
                }
            });
        return module;
    }
}
