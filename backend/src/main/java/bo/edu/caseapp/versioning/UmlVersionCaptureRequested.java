package bo.edu.caseapp.versioning;

import java.util.UUID;

public record UmlVersionCaptureRequested(UUID diagramId, String actorEmail, long revision, String source) { }
