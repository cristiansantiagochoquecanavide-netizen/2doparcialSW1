package bo.edu.caseapp.versioning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class UmlVersionCaptureListener {
  private static final Logger log = LoggerFactory.getLogger(UmlVersionCaptureListener.class);
  private final VersionService versions;
  public UmlVersionCaptureListener(VersionService versions) { this.versions = versions; }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void capture(UmlVersionCaptureRequested event) {
    try { versions.captureAutomatic(event); }
    catch (RuntimeException error) { log.error("No se pudo registrar la versión automática del diagrama {} en revisión {}",
        event.diagramId(), event.revision(), error); }
  }
}
