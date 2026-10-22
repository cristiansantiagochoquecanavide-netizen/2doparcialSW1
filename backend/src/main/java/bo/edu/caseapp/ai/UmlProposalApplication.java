package bo.edu.caseapp.ai;

import bo.edu.caseapp.error.*;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class UmlProposalApplication {
  private final UmlProposalExecutor executor; private final UmlProposalDecisions decisions;
  public UmlProposalApplication(UmlProposalExecutor executor,UmlProposalDecisions decisions) { this.executor=executor; this.decisions=decisions; }
  public UmlProposalStore.View apply(UUID projectId,UUID diagramId,UUID id,String email,UmlProposalExecutor.Apply request) {
    try { return executor.apply(projectId,diagramId,id,email,request); }
    catch(UmlProposalExecutor.StaleProposal stale) { decisions.failure(projectId,diagramId,id,email,true); throw stale; }
    catch(UmlProposalExecutor.ConfirmationRequired expected) { throw expected; }
    catch(ForbiddenException|NotFoundException|ConflictException expected) { throw expected; }
    catch(RuntimeException failure) {
      decisions.failure(projectId,diagramId,id,email,false);
      throw new BadRequestException("La propuesta no pudo aplicarse. No se confirmó ningún cambio UML.");
    }
  }
}
