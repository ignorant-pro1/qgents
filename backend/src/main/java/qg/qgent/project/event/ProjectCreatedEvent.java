package qg.qgent.project.event;

public record ProjectCreatedEvent(Long projectId, String projectName, Long creatorUserId) {
}
