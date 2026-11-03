package bo.edu.caseapp.uml;

import bo.edu.caseapp.error.BadRequestException;
import bo.edu.caseapp.error.NotFoundException;
import bo.edu.caseapp.project.ProjectService;
import bo.edu.caseapp.user.User;
import bo.edu.caseapp.user.UserRepository;
import bo.edu.caseapp.uml.UmlCommentRequests.Create;
import bo.edu.caseapp.uml.UmlCommentRequests.Update;
import bo.edu.caseapp.uml.UmlCommentResponses.CommentView;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class UmlCommentService {
  private final UmlCommentRepository comments;
  private final DiagramaClaseRepository diagrams;
  private final ElementoUMLRepository elements;
  private final RelacionUMLRepository relations;
  private final UserRepository users;
  private final ProjectService projects;

  public UmlCommentService(UmlCommentRepository comments, DiagramaClaseRepository diagrams, ElementoUMLRepository elements,
      RelacionUMLRepository relations, UserRepository users, ProjectService projects) {
    this.comments = comments; this.diagrams = diagrams; this.elements = elements; this.relations = relations; this.users = users; this.projects = projects;
  }

  @Transactional(readOnly = true)
  public List<CommentView> list(UUID projectId, UUID diagramId, String email) {
    projects.requireAccess(projectId, email); diagram(projectId, diagramId);
    return comments.findByDiagramaIdOrderByCreatedAtAsc(diagramId).stream().map(this::view).toList();
  }

  public CommentView create(UUID projectId, UUID diagramId, String email, Create request) {
    projects.requireCanEdit(projectId, email); DiagramaClase diagram = diagram(projectId, diagramId);
    User author = user(email); ElementoUML element = null; RelacionUML relation = null;
    if (request.elementoId() != null) {
      element = elements.findById(request.elementoId()).filter(value -> value.getDiagrama().getId().equals(diagramId)).orElseThrow(() -> new NotFoundException("Clase no encontrada."));
    }
    if (request.relacionId() != null) {
      relation = relations.findById(request.relacionId()).filter(value -> value.getDiagrama().getId().equals(diagramId)).orElseThrow(() -> new NotFoundException("Relación no encontrada."));
    }
    boolean canvasTarget = request.posicionX() != null && request.posicionY() != null;
    if (canvasTarget && (element != null || relation != null)) throw new BadRequestException("El comentario de lienzo no puede combinarse con una clase o relación.");
    if (!canvasTarget && (element == null) == (relation == null)) throw new BadRequestException("El comentario debe apuntar a un punto del lienzo.");
    UmlComment parent = null;
    if (request.parentId() != null) parent = comments.findByIdAndDiagramaId(request.parentId(), diagramId).orElseThrow(() -> new NotFoundException("Comentario padre no encontrado."));
    return view(comments.save(new UmlComment(diagram, author, element, relation, parent, request.content().trim(), request.posicionX(), request.posicionY())));
  }

  public CommentView updateStatus(UUID projectId, UUID diagramId, UUID commentId, String email, String status) {
    projects.requireCanEdit(projectId, email); diagram(projectId, diagramId); User user = user(email);
    UmlComment comment = comments.findByIdAndDiagramaId(commentId, diagramId).orElseThrow(() -> new NotFoundException("Comentario no encontrado."));
    if ("RESOLVED".equalsIgnoreCase(status)) comment.resolve(user);
    else if ("OPEN".equalsIgnoreCase(status)) comment.reopen();
    else throw new BadRequestException("Estado de comentario no válido.");
    return view(comment);
  }

  public CommentView update(UUID projectId, UUID diagramId, UUID commentId, String email, Update request) {
    projects.requireCanEdit(projectId, email); diagram(projectId, diagramId);
    UmlComment comment = comments.findByIdAndDiagramaId(commentId, diagramId).orElseThrow(() -> new NotFoundException("Comentario no encontrado."));
    if (request.posicionX() != null && request.posicionY() != null) comment.updateCanvas(request.content(), request.posicionX(), request.posicionY(), request.canvasWidth(), request.canvasHeight());
    else if (request.content() != null || request.canvasWidth() != null || request.canvasHeight() != null) comment.updateCanvas(request.content(), null, null, request.canvasWidth(), request.canvasHeight());
    else throw new BadRequestException("Debe indicar el texto o la posición del comentario.");
    return view(comment);
  }

  public void delete(UUID projectId, UUID diagramId, UUID commentId, String email) {
    projects.requireCanEdit(projectId, email); diagram(projectId, diagramId);
    UmlComment comment = comments.findByIdAndDiagramaId(commentId, diagramId).orElseThrow(() -> new NotFoundException("Comentario no encontrado."));
    comments.delete(comment);
  }

  private DiagramaClase diagram(UUID projectId, UUID diagramId) {
    DiagramaClase diagram = diagrams.findById(diagramId).orElseThrow(() -> new NotFoundException("Diagrama no encontrado."));
    if (!diagram.getProject().getId().equals(projectId)) throw new NotFoundException("Diagrama no encontrado.");
    return diagram;
  }
  private User user(String email) { return users.findByEmailIgnoreCase(email).orElseThrow(() -> new NotFoundException("Usuario no encontrado.")); }
  private CommentView view(UmlComment c) {
    String source = c.getRelacion() == null ? null : c.getRelacion().getElementoOrigen().getNombre();
    String target = c.getRelacion() == null ? null : c.getRelacion().getElementoDestino().getNombre();
    return new CommentView(c.getId(), c.getParent() == null ? null : c.getParent().getId(), c.getAuthor().getName(), c.getAuthor().getEmail(),
        c.getElemento() == null ? null : c.getElemento().getId(), c.getRelacion() == null ? null : c.getRelacion().getId(),
        c.getElemento() == null ? null : c.getElemento().getNombre(), source, target, c.getPosicionX(), c.getPosicionY(), c.getCanvasWidth(), c.getCanvasHeight(), c.getContent(), c.getStatus().name(), c.getCreatedAt(), c.getUpdatedAt(),
        c.getResolvedBy() == null ? null : c.getResolvedBy().getName(), c.getResolvedAt());
  }
}
