package bo.edu.caseapp.ai;

import bo.edu.caseapp.uml.TipoElementoUML;
import bo.edu.caseapp.uml.TipoRelacionUML;
import bo.edu.caseapp.uml.validation.ValidationIssue;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

public final class UmlProposalContract {
  private UmlProposalContract() { }
  public enum OperationType { CREATE_CLASS, UPDATE_CLASS, DELETE_CLASS, ADD_ATTRIBUTE, UPDATE_ATTRIBUTE,
    DELETE_ATTRIBUTE, ADD_METHOD, UPDATE_METHOD, DELETE_METHOD, CREATE_RELATION, UPDATE_RELATION,
    DELETE_RELATION, UPDATE_MULTIPLICITY }
  public record Operation(OperationType type, String ref, String target, String memberId, String name,
      TipoElementoUML classType, String description, String dataType, String visibility, String parameters,
      String source, String destination, TipoRelacionUML relationType, String sourceMultiplicity, String targetMultiplicity) { }
  public record Plan(String description, String clarification, List<Operation> operations) { }
  public enum ChangeKind { CREATE, UPDATE, DELETE }
  public enum EntityKind { CLASS, RELATION }
  public record Change(ChangeKind kind, EntityKind entity, UUID id, JsonNode before, JsonNode after) { }
  public record Preview(List<Change> changes, List<ValidationIssue> warnings, boolean destructive, String clarification) { }
  public enum Status { PENDIENTE, ACLARACION, APLICADA, RECHAZADA, CONFLICTO, FALLIDA }
}
