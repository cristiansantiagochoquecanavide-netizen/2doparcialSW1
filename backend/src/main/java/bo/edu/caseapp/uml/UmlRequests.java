package bo.edu.caseapp.uml;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;

public final class UmlRequests {
  private UmlRequests() { }

  public record CreateDiagram(
      @NotBlank @Size(max = 120) String nombre,
      @Size(max = 500) String descripcion
  ) { }

  public record UpdateDiagram(
      @NotBlank @Size(max = 120) String nombre,
      @Size(max = 500) String descripcion,
      Long expectedRevision
  ) {
    public UpdateDiagram(String nombre, String descripcion) {
      this(nombre, descripcion, null);
    }
  }

  public record SaveElement(
      UUID id,
      @NotNull TipoElementoUML tipoElemento,
      @NotBlank @Size(max = 120) String nombre,
      @NotNull Double posicionX,
      @NotNull Double posicionY,
      String propiedadesJson,
      Long expectedRevision
  ) {
    public SaveElement(UUID id, TipoElementoUML tipoElemento, String nombre, Double posicionX, Double posicionY, String propiedadesJson) {
      this(id, tipoElemento, nombre, posicionX, posicionY, propiedadesJson, null);
    }
  }

  public record SaveRelation(
      UUID id,
      @NotNull UUID elementoOrigen,
      @NotNull UUID elementoDestino,
      @Size(max = 120) String nombreRelacion,
      @NotNull TipoRelacionUML tipoRelacion,
      @Size(max = 20) String multiplicidadOrigen,
      @Size(max = 20) String multiplicidadDestino,
      Long expectedRevision
  ) {
    public SaveRelation(UUID id, UUID elementoOrigen, UUID elementoDestino, String nombreRelacion, TipoRelacionUML tipoRelacion, String multiplicidadOrigen, String multiplicidadDestino) {
      this(id, elementoOrigen, elementoDestino, nombreRelacion, tipoRelacion, multiplicidadOrigen, multiplicidadDestino, null);
    }
  }

  public record CreateIntermediateClass(
      @NotBlank @Size(max = 120) String nombre,
      @Size(max = 50) List<@Valid IntermediateAttribute> atributos
  ) { }

  public record IntermediateAttribute(
      @NotBlank @Size(max = 120) String nombre,
      @NotBlank @Size(max = 120) String tipo
  ) { }

  public record PositionUpdate(
      @NotNull UUID id,
      @NotNull Double posicionX,
      @NotNull Double posicionY
  ) { }

  public record SyncPositions(
      @NotNull List<PositionUpdate> positions,
      Long expectedRevision
  ) {
    public SyncPositions(List<PositionUpdate> positions) {
      this(positions, null);
    }
  }

  public record DeleteElementsRequest(
      @NotEmpty List<@NotNull UUID> elementIds
  ) { }
}
