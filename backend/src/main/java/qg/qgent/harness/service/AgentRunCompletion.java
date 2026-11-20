package qg.qgent.harness.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.chat.service.MessageService;
import qg.qgent.harness.entity.AgentRunEntity;
import qg.qgent.harness.entity.RunStatus;

/** 原子提交根运行的终态和它的群聊回复。 */
@Service
@RequiredArgsConstructor
public class AgentRunCompletion {
    private final AgentRunService runs;
    private final MessageService messages;

    @Transactional
    public AgentRunEntity complete(Long runId, RunStatus status, String result, int rounds) {
        AgentRunEntity run = runs.findRun(runId);
        if (run != null && runs.finishRoot(run, status, result, rounds)) {
            String text = run.getResult() == null ? "Agent 本次运行未返回文本" : run.getResult();
            messages.sendAgentRunReply(run.getAgentId(), run.getGroupId(), run.getTriggerMessageId(),
                run.getId(), text);
        }
        return run;
    }
}
