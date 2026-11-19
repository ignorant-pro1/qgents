package qg.qgent.harness.runtime;

/**
 * 运行中向实时层上报可展示进度；实现必须忽略已结束运行的迟到回调。
 */
public interface RunStreamSink {
    /**
     * 新的模型轮次开始，实时层分配自己的单调轮次编号。
     */
    void roundStarted();

    /**
     * 模型正文增量，可分批上报。
     */
    void delta(String text);

    /**
     * input 是模型给出的原始入参，可为 null。
     */
    void toolStarted(String toolCallId, String toolName, String input);

    /**
     * output 是工具返回的原始结果，可为 null。ok 为 false 表示失败；null 表示工具结束但未声明结果，
     * 前端按中性“结束”展示。
     */
    void toolFinished(String toolCallId, String toolName, Boolean ok, String output);

    /**
     * 运行终态；由收尾流程在数据库状态写好之后调用，终态取自运行记录。
     */
    void finished();

    RunStreamSink NONE =
        new RunStreamSink() {
            public void roundStarted() {
            }

            public void delta(String text) {
            }

            public void toolStarted(String toolCallId, String toolName, String input) {
            }

            public void toolFinished(
                String toolCallId, String toolName, Boolean ok, String output) {
            }

            public void finished() {
            }
        };
}
