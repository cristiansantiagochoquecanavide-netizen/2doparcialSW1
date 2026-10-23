package bo.edu.caseapp.chat;

import java.util.UUID;

/** Metadata only. Private conversations never use this project-wide event. */
public record ProjectChannelsChanged(UUID projectId, UUID channelId, String channelType, String action) {}
