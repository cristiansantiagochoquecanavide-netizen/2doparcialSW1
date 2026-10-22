package bo.edu.caseapp.ai;

import bo.edu.caseapp.ai.AiConversationResponses.Message;
import java.util.UUID;

public record AiConversationMessageCommittedEvent(UUID projectId, UUID conversationId, Message message) { }
