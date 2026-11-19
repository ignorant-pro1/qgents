package qg.qgent.workspace.service;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 一次写入租约的持有者信息和服务端令牌。
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class WorkspaceWriteLease {
    @Getter
    private final Long projectId;
    @Getter
    private final Long workspaceId;
    @Getter
    private final Long agentRunId;
    private final String token;

    String token() {
        return token;
    }
}
