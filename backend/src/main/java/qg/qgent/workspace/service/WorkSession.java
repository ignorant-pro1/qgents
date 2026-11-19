package qg.qgent.workspace.service;

/**
 * 单个 Agent 对话的工作区会话：当前选择、当前目录，以及按需准备沙箱环境。
 * <p>
 * 工具只通过这个接口使用工作区；环境如何建立、租约如何持有属于实现。
 */
public interface WorkSession {

    /**
     * 当前选中的 Workspace；尚未选择时为 null。
     */
    Long workspaceId();

    /**
     * 当前目录，相对 Workspace 根。
     */
    String directory();

    /**
     * 用当前选择准备代码环境；未选择时使用项目默认 Workspace。
     */
    Long ensure();

    /**
     * 准备指定 Workspace 的代码环境，不改变当前选择。
     */
    Long ensure(Long workspaceId);

    /**
     * 当前选择已初始化时返回它，否则提示先初始化。
     */
    Long requireInitialized();

    /**
     * 切换当前选择并重置目录。
     */
    void select(Long workspaceId);

    void setDirectory(String directory);
}
