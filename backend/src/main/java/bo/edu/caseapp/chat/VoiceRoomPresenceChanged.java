package bo.edu.caseapp.voice;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Snapshot of the existing signaling room; observing never adds a participant. */
public record VoiceRoomPresenceChanged(UUID projectId, UUID roomId, String serverId, long revision,
    int maxParticipants, List<Map<String, Object>> participants) { }
