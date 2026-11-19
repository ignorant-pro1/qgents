package qg.qgent.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import qg.qgent.auth.security.JwtAuthenticationFilter;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 配置 JWT 鉴权、公开接口和跨域规则。
 */
@Slf4j
@Configuration
public class SecurityConfiguration {

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    ObjectMapper objectMapper(com.fasterxml.jackson.databind.Module utcLocalDateTimeModule) {
        return new ObjectMapper().findAndRegisterModules().registerModule(utcLocalDateTimeModule);
    }

    /**
     * 使用 BCrypt 保存密码哈希。
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * 配置无状态认证及请求访问规则。
     */
    @Bean
    SecurityFilterChain security(
        HttpSecurity http,
        JwtAuthenticationFilter jwt,
        ObjectMapper mapper,
        @Qualifier("cors") CorsConfigurationSource corsConfigurationSource)
        throws Exception {
        return http.csrf(c -> c.disable())
            .cors(c -> c.configurationSource(corsConfigurationSource))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a ->
                a
                    // SSE 断连/超时后容器触发的 ASYNC/ERROR dispatch 会重走过滤链，此时已无 SecurityContext
                    // （且长连接可能已超出 access token 有效期）。派发是已通过初始鉴权的请求的收尾，
                    // 不构成新的授权入口，因此放行；初始 REQUEST 仍走完整鉴权。
                    .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers(HttpMethod.OPTIONS, "/**")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/auth/register",
                        "/api/v1/auth/register/verification-codes",
                        "/api/v1/auth/login",
                        "/api/v1/auth/refresh",
                        "/api/v1/auth/logout",
                        "/api/v1/auth/password-reset-requests",
                        "/api/v1/auth/password-resets")
                    .permitAll()
                    // App 回调通过一次性 state 识别安装发起人；Webhook 单独校验签名。
                    .requestMatchers(
                        HttpMethod.GET, "/api/v1/integrations/github/callback")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.POST, "/api/v1/integrations/github/webhook")
                    .permitAll()
                    // SSE 断线时容器触发 async dispatch 的兜底；初始请求的成员鉴权由
                    // EventController/EventService 的 requireProjectMember 保证。
                    .requestMatchers(HttpMethod.GET, "/api/v1/projects/*/events")
                    .permitAll()
                    // WebSocket 握手升级为 HTTP GET，需放行初始请求；真实鉴权由
                    // RealtimeAuthInterceptor 解析 query token 完成（浏览器升级无法携带 Authorization
                    // 头）。
                    .requestMatchers("/api/v1/ws/realtime")
                    .permitAll()
                    // Prometheus 在本地监控端口抓取指标，不携带业务 JWT；只暴露
                    // 监控所需的只读端点，业务接口仍必须登录。
                    .requestMatchers(
                        "/actuator/prometheus",
                        "/actuator/health",
                        "/actuator/info")
                    .permitAll()
                    .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/error")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
            .exceptionHandling(e ->
                e.authenticationEntryPoint((req, res, ex) -> {
                    // 401 原因记录为 debug：token 缺失/过期属客户端常态，避免刷屏；排查时开启 DEBUG 级别即可
                    log.debug(
                        "Unauthorized {} {}: {}",
                        req.getMethod(),
                        req.getRequestURI(),
                        ex.getMessage());
                    res.setStatus(401);
                    res.setCharacterEncoding("UTF-8");
                    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    mapper.writeValue(
                        res.getWriter(),
                        Map.of(
                            "error",
                            Map.of(
                                "code",
                                "UNAUTHORIZED",
                                "message",
                                "需要登录",
                                "details",
                                List.of()),
                            "requestId",
                            String.valueOf(req.getAttribute("requestId"))));
                }))
            .addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class)
            .build();
    }

    /**
     * 按配置允许前端来源，开放业务请求和 SSE 续传所需的请求头。
     */
    @Bean
    CorsConfigurationSource cors(@Value("${app.cors-allowed-origins}") String origins) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(
            Arrays.stream(origins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList());
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // 允许的请求头：Last-Event-ID（SSE 续传）
        c.setAllowedHeaders(
            List.of("Authorization", "Content-Type", "X-Request-Id", "Last-Event-ID"));
        // 当前认证使用 Authorization Bearer，不启用跨域凭证（Cookie）传递。
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", c);
        return source;
    }
}
