package bo.edu.caseapp.chat;

import java.util.UUID;

public record ChatMessageCommittedEvent(UUID projectId, ChatResponses.Message message) {}
