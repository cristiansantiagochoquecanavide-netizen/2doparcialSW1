package bo.edu.caseapp.ai;

import bo.edu.caseapp.ai.AiRequests.ChatRequest;
import bo.edu.caseapp.ai.AiRequests.CommandRequest;
import bo.edu.caseapp.ai.AiResponses.ChatResponse;
import bo.edu.caseapp.ai.AiResponses.CommandResponse;
import bo.edu.caseapp.ai.AiResponses.InteractionHistoryItem;
import bo.edu.caseapp.uml.UmlService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/diagrams/{diagramId}/ai")
public class AiController {
  private final AiAssistantService aiService;
  private final InteraccionIaRepository iaRepo;
  private final UmlService umlService;
  private final GroqTranscriptionService transcriptionService;

  @Autowired
  public AiController(AiAssistantService aiService, InteraccionIaRepository iaRepo, UmlService umlService, GroqTranscriptionService transcriptionService) {
    this.aiService = aiService;
    this.iaRepo = iaRepo;
    this.umlService = umlService;
    this.transcriptionService = transcriptionService;
  }

  public AiController(AiAssistantService aiService, InteraccionIaRepository iaRepo, UmlService umlService) {
    this(aiService, iaRepo, umlService, null);
  }


  @PostMapping(value = "/transcribe", consumes = "multipart/form-data")
  public AiResponses.TranscriptionResponse transcribe(
      @PathVariable UUID projectId,
      @PathVariable UUID diagramId,
      Authentication auth,
      @RequestPart("audio") MultipartFile audio
  ) {
    umlService.requireDiagramAccess(projectId, diagramId, auth.getName());
    return new AiResponses.TranscriptionResponse(transcriptionService.transcribe(audio));
  }

  @PostMapping("/command")
  public CommandResponse command(
      @PathVariable UUID projectId,
      @PathVariable UUID diagramId,
      Authentication auth,
      @Valid @RequestBody CommandRequest request,
      @RequestHeader(value = "X-Diagram-Revision", required = false) Long revisionHeader,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      jakarta.servlet.http.HttpServletResponse response
  ) {
    Long expectedRev = revisionHeader != null ? revisionHeader : (ifMatch != null && !ifMatch.isBlank() ? Long.parseLong(ifMatch.replace("\"", "").trim()) : null);
    CommandResponse res = aiService.executeCommand(projectId, diagramId, auth.getName(), request, expectedRev);
    long currentRev = umlService.getDiagramRevision(diagramId);
    response.setHeader("X-Diagram-Revision", String.valueOf(currentRev));
    return res;
  }

  @PostMapping("/chat")
  public ChatResponse chat(
      @PathVariable UUID projectId,
      @PathVariable UUID diagramId,
      Authentication auth,
      @Valid @RequestBody ChatRequest request
  ) {
    return aiService.chat(projectId, diagramId, auth.getName(), request);
  }

  @PostMapping({"/apply-image-model", "/reconstruct-image"})
  public AiResponses.ImageReconstructionResponse applyImageModel(
      @PathVariable UUID projectId,
      @PathVariable UUID diagramId,
      Authentication auth,
      @Valid @RequestBody AiRequests.ApplyImageModelRequest request,
      @RequestHeader(value = "X-Diagram-Revision", required = false) Long revisionHeader,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      jakarta.servlet.http.HttpServletResponse response
  ) {
    Long expectedRev = requireExpectedRevision(revisionHeader, ifMatch);
    AiResponses.ImageReconstructionResponse res = aiService.applyImageModel(projectId, diagramId, auth.getName(), request, expectedRev);
    if (res.updatedDiagram() != null && res.updatedDiagram().diagram() != null) {
      response.setHeader("X-Diagram-Revision", String.valueOf(res.updatedDiagram().diagram().revision()));
    }
    return res;
  }

  private Long requireExpectedRevision(Long headerRevision, String ifMatch) {
    if (headerRevision != null) return headerRevision;
    if (ifMatch != null && !ifMatch.isBlank()) {
      try {
        return Long.parseLong(ifMatch.replace("\"", "").trim());
      } catch (NumberFormatException ignored) {}
    }
    throw new bo.edu.caseapp.error.BadRequestException(
        "Se requiere la revisión esperada del diagrama ('X-Diagram-Revision' o 'If-Match') para verificar la concurrencia optimista.");
  }

  @PostMapping("/analyze-image")
  public AiResponses.ImageAnalysisResponse analyzeImage(
      @PathVariable UUID projectId,
      @PathVariable UUID diagramId,
      Authentication auth,
      @Valid @RequestBody AiRequests.AnalyzeImageRequest request
  ) {
    return aiService.analyzeImage(projectId, diagramId, auth.getName(), request);
  }

  @GetMapping("/history")
  public List<InteractionHistoryItem> history(
      @PathVariable UUID projectId,
      @PathVariable UUID diagramId,
      Authentication auth
  ) {
    String email = auth.getName();
    umlService.requireDiagramAccess(projectId, diagramId, email);
    return iaRepo.findByDiagramaIdAndUsuarioEmailIgnoreCaseOrderByFechaDesc(diagramId, email)
        .stream()
        .map(i -> new InteractionHistoryItem(i.getId(), i.getUsuario().getId(), i.getUsuario().getName(),
            i.getTipoInteraccion(), i.getEntradaUsuario(), i.getRespuestaAgente(), i.getFecha()))
        .toList();
  }
}
