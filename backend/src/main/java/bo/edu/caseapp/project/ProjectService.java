package bo.edu.caseapp.project;

import bo.edu.caseapp.error.*;
import bo.edu.caseapp.project.ProjectRequests.*;
import bo.edu.caseapp.project.ProjectResponses.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import bo.edu.caseapp.user.User;
import bo.edu.caseapp.user.UserRepository;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ProjectService {
  private final ProjectRepository projects; private final ProjectMemberRepository members; private final UserRepository users;
  private final ApplicationEventPublisher events;
  @Autowired
  public ProjectService(ProjectRepository projects, ProjectMemberRepository members, UserRepository users, ApplicationEventPublisher events) { this.projects = projects; this.members = members; this.users = users; this.events = events; }
  public ProjectService(ProjectRepository projects, ProjectMemberRepository members, UserRepository users) { this(projects, members, users, event -> {}); }
  @Transactional(readOnly = true)
  public List<ProjectSummary> list(String email) {
    User user = user(email); return members.findByUserId(user.getId()).stream().map(m -> new ProjectSummary(m.getProject().getId(), m.getProject().getName(), m.getProject().getDescription(), m.getRole(), m.getProject().getCreatedAt())).toList();
  }
  public ProjectDetail create(String email, Create request) {
    User creator = user(email); Project project = projects.save(new Project(creator, request.name().trim(), clean(request.description())));
    members.save(new ProjectMember(project, creator, ProjectRole.PROPIETARIO)); return detail(project, ProjectRole.PROPIETARIO);
  }
  @Transactional(readOnly = true)
  public ProjectDetail get(UUID projectId, String email) { ProjectMember member = membership(projectId, email); return detail(member.getProject(), member.getRole()); }
  public ProjectDetail update(UUID projectId, String email, Update request) {
    ProjectMember member = membership(projectId, email); requireCanEdit(member); member.getProject().update(request.name().trim(), clean(request.description())); return detail(member.getProject(), member.getRole());
  }
  public void delete(UUID projectId, String email) { ProjectMember member = membership(projectId, email); requireOwner(member); events.publishEvent(new ProjectAccessRevokedEvent(projectId, null)); projects.delete(member.getProject()); }
  @Transactional(readOnly = true)
  public List<Member> listMembers(UUID projectId, String email) { membership(projectId, email); return members.findByProjectId(projectId).stream().map(this::member).toList(); }
  public Member addMember(UUID projectId, String email, AddMember request) {
    requireOwner(membership(projectId, email));
    if (request.role() == ProjectRole.PROPIETARIO) throw new BadRequestException("El rol PROPIETARIO solo se asigna al creador del proyecto.");
    User collaborator = users.findByEmailIgnoreCase(request.email().trim()).orElseThrow(() -> new NotFoundException("No existe un usuario con ese correo."));
    if (members.findByProjectIdAndUserId(projectId, collaborator.getId()).isPresent()) throw new BadRequestException("El usuario ya pertenece al proyecto.");
    Project project = projects.getReferenceById(projectId); return member(members.save(new ProjectMember(project, collaborator, request.role())));
  }
  public Member updateMember(UUID projectId, UUID memberId, String email, UpdateMember request) {
    requireOwner(membership(projectId, email)); ProjectMember target = memberForProject(projectId, memberId);
    if (target.getRole() == ProjectRole.PROPIETARIO || request.role() == ProjectRole.PROPIETARIO) throw new BadRequestException("No se puede modificar el rol del propietario.");
    target.setRole(request.role()); return member(target);
  }
  public void removeMember(UUID projectId, UUID memberId, String email) {
    requireOwner(membership(projectId, email)); ProjectMember target = memberForProject(projectId, memberId);
    if (target.getRole() == ProjectRole.PROPIETARIO) throw new BadRequestException("No se puede eliminar al propietario.");
    events.publishEvent(new ProjectAccessRevokedEvent(projectId, target.getUser().getEmail()));
    members.delete(target);
  }
  public ProjectMember requireAccess(UUID projectId, String email) { return membership(projectId, email); }
  public ProjectMember requireCanEdit(UUID projectId, String email) { ProjectMember member = membership(projectId, email); requireCanEdit(member); return member; }
  private User user(String email) { return users.findByEmailIgnoreCase(email).orElseThrow(() -> new NotFoundException("Usuario no encontrado.")); }
  private ProjectMember membership(UUID projectId, String email) { User user = user(email); return members.findByProjectIdAndUserId(projectId, user.getId()).orElseThrow(() -> new ForbiddenException("No tienes acceso a este proyecto.")); }
  private ProjectMember memberForProject(UUID projectId, UUID memberId) { ProjectMember member = members.findById(memberId).orElseThrow(() -> new NotFoundException("Colaborador no encontrado.")); if (!member.getProject().getId().equals(projectId)) throw new NotFoundException("Colaborador no encontrado."); return member; }
  private void requireOwner(ProjectMember member) { if (member.getRole() != ProjectRole.PROPIETARIO) throw new ForbiddenException("Esta acción requiere ser propietario del proyecto."); }
  private void requireCanEdit(ProjectMember member) { if (member.getRole() == ProjectRole.LECTOR) throw new ForbiddenException("El rol LECTOR no puede editar el proyecto."); }
  private ProjectDetail detail(Project project, ProjectRole role) { return new ProjectDetail(project.getId(), project.getName(), project.getDescription(), role, project.getCreatedAt()); }
  private Member member(ProjectMember member) { User user = member.getUser(); return new Member(member.getId(), user.getId(), user.getName(), user.getEmail(), member.getRole()); }
  private String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
