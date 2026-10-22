package bo.edu.caseapp.project;

import java.util.UUID;

/** Published after a membership is removed or its project is deleted. */
public record ProjectAccessRevokedEvent(UUID projectId, String userEmail) {}
