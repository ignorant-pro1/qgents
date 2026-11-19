package qg.qgent.sandbox.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * 沙箱容器的运行时、资源上限和回收配置。
 * 所有值均由部署环境控制，客户端请求不能放宽这些限制。
 */
@Data
@ConfigurationProperties(prefix = "sandbox")
public class SandboxProperties {

    // 容器运行时实现，可选值为 fake 或 docker。
    private String runtime = "docker";

    // 未发生操作时的默认存活时间。
    private Duration defaultIdleTtl = Duration.ofMinutes(30);

    // 沙箱允许的最大空闲存活时间。
    private Duration maxIdleTtl = Duration.ofHours(2);

    // 沙箱从创建开始计算的默认最大生命周期。
    private Duration defaultMaxLifetime = Duration.ofHours(4);

    // 沙箱生命周期的 Sandbox 本地硬上限。
    private Duration maxLifetime = Duration.ofHours(12);

    // 单次命令的默认执行超时时间。
    private Duration defaultExecutionTimeout = Duration.ofMinutes(15);

    // 单次命令执行超时的 Sandbox 本地硬上限。
    private Duration maxExecutionTimeout = Duration.ofHours(1);

    // 异步命令执行线程池大小。
    private int executionThreads = 4;

    // Docker Engine API 地址。
    private String dockerHost = "unix:///var/run/docker.sock";

    // 当前应用实例的稳定编号，用于容器恢复和执行归属。
    private String instanceId = "local";

    // 工作区元数据目录，不挂载到沙箱。
    private String workspaceMetadataRoot = "/var/lib/qgents/workspace-metadata";

    // 镜像配置名称到实际镜像引用的映射。
    private Map<String, String> images = Map.of("dev-tools", "qgents/sandbox-dev-tools:0.2.0");

    // 单个沙箱可使用的内存字节数。
    private long memoryBytes = 4L * 1024 * 1024 * 1024;

    // Docker nanoCPUs 配置值，十亿表示一个 CPU 核心。
    private long nanoCpus = 2_000_000_000L;

    // 单个沙箱允许创建的最大进程数量。
    private long pidsLimit = 256L;

    // 网络策略：none 禁止网络，outbound 使用 Docker bridge 网络。
    private String networkPolicy = "outbound";

    // stdout 和 stderr 各自在 Sandbox 内存中允许保留的最大字节数。
    private int maxOutputBytes = 1024 * 1024;

    // 单个 Sandbox 的 Maven / Maven Wrapper 临时缓存容量上限（Docker tmpfs size，如 2g、512m 或字节数）。
    // 只作为本 Sandbox 的可写 tmpfs，Sandbox 销毁后即清理，不是宿主机持久缓存，也不与其他 Sandbox 或 Project 共享。
    private String mavenCacheSize = "2g";

    // 单个 Sandbox 的 Gradle 临时缓存容量上限（Docker tmpfs size，如 3g、512m 或字节数）。
    // 只作为本 Sandbox 的可写 tmpfs，Sandbox 销毁后即清理，不是宿主机持久缓存，也不与其他 Sandbox 或 Project 共享。
    private String gradleCacheSize = "3g";

    // 单个 Sandbox 的 npm 临时缓存容量上限（Docker tmpfs size，如 1g、512m 或字节数）。
    // 只作为本 Sandbox 的可写 tmpfs，Sandbox 销毁后即清理，不是宿主机持久缓存，也不与其他 Sandbox 或 Project 共享。
    private String npmCacheSize = "1g";

    // 沙箱开发用户 HOME 的临时可写容量，覆盖各构建工具的隐藏状态目录。
    private String developerHomeSize = "8g";

    // 新建沙箱默认使用的镜像配置。
    private String imageProfile = "dev-tools";
}
