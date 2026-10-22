package bo.edu.caseapp.ai;

import static bo.edu.caseapp.ai.UmlProposalContract.*;
import bo.edu.caseapp.error.BadRequestException;
import bo.edu.caseapp.uml.*;
import bo.edu.caseapp.uml.UmlResponses.*;
import bo.edu.caseapp.uml.validation.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;

/** Pure projection: neither an LLM response nor a preview can write UML. */
@Component
public class UmlProposalEngine {
  private final ObjectMapper mapper;
  private final UmlValidatorService validator;
  public UmlProposalEngine(ObjectMapper mapper, UmlValidatorService validator) { this.mapper=mapper; this.validator=validator; }
  public Plan parse(String json) {
    try {
      if (json == null || json.getBytes(StandardCharsets.UTF_8).length > 65536) throw invalid();
      String source=json.trim();
      if (source.startsWith("```")) source=source.replaceFirst("^```(?:json)?\\s*","").replaceFirst("\\s*```$","");
      Plan plan=mapper.readerFor(Plan.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
          DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
          .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readValue(source);
      text(plan.description(),2000); if (plan.operations()==null || plan.operations().size()>32) throw invalid();
      if (plan.clarification()!=null && !plan.clarification().isBlank()) {
        text(plan.clarification(),2000); if (!plan.operations().isEmpty()) throw invalid();
      } else if (plan.operations().isEmpty()) throw invalid();
      return plan;
    } catch (Exception failure) { throw invalid(); }
  }

  public Preview compile(DiagramFullView base, Plan plan) {
    if (plan.clarification()!=null && !plan.clarification().isBlank()) return new Preview(List.of(),List.of(),false,plan.clarification());
    Map<UUID,ObjectNode> originalClasses=classes(base), originalRelations=relations(base);
    Map<UUID,ObjectNode> nodes=copy(originalClasses), links=copy(originalRelations);
    Map<String,UUID> temporary=new HashMap<>();
    for (Operation op:plan.operations()) {
      if (op==null || op.type()==null) throw invalid();
      switch(op.type()) {
        case CREATE_CLASS -> {
          UUID id=allocate(op.ref(),temporary);
          ObjectNode properties=mapper.createObjectNode(); properties.putArray("attributes"); properties.putArray("methods");
          if (op.description()!=null) properties.put("description",text(op.description(),2000));
          ObjectNode node=mapper.createObjectNode().put("id",id.toString()).put("name",identifier(op.name(),120))
              .put("type",(op.classType()==null?TipoElementoUML.CLASE:op.classType()).name())
              .put("x",120+(nodes.size()%3)*340).put("y",100+(nodes.size()/3)*260);
          node.set("properties",properties); nodes.put(id,node);
        }
        case UPDATE_CLASS -> {
          ObjectNode node=nodes.get(resolve(op.target(),temporary,nodes));
          if (op.name()!=null) node.put("name",identifier(op.name(),120));
          if (op.classType()!=null) node.put("type",op.classType().name());
          if (op.description()!=null) ((ObjectNode)node.get("properties")).put("description",text(op.description(),2000));
        }
        case DELETE_CLASS -> {
          UUID id=resolve(op.target(),temporary,nodes); nodes.remove(id);
          links.values().removeIf(link -> link.path("source").asText().equals(id.toString()) || link.path("destination").asText().equals(id.toString()));
        }
        case ADD_ATTRIBUTE,UPDATE_ATTRIBUTE,DELETE_ATTRIBUTE,ADD_METHOD,UPDATE_METHOD,DELETE_METHOD -> {
          ObjectNode node=nodes.get(resolve(op.target(),temporary,nodes));
          String group=op.type().name().endsWith("ATTRIBUTE")?"attributes":"methods";
          ObjectNode properties=(ObjectNode)node.get("properties");
          if (!properties.has(group)) properties.putArray(group);
          if (!properties.get(group).isArray()) throw invalid();
          ArrayNode members=(ArrayNode)properties.get(group);
          boolean add=op.type().name().startsWith("ADD_"), delete=op.type().name().startsWith("DELETE_");
          ObjectNode member;
          if (add) { member=members.addObject().put("id",UUID.randomUUID().toString()); }
          else {
            member=null;
            for(int index=0;index<members.size();index++) if (members.get(index).path("id").asText().equals(op.memberId())) {
              if (delete) { members.remove(index); member=mapper.createObjectNode(); }
              else if (members.get(index).isObject()) member=(ObjectNode)members.get(index);
              break;
            }
            if(member==null) throw new BadRequestException("El atributo o método no existe en la clase indicada.");
          }
          if (!delete) {
            if (add || op.name()!=null) member.put("name",identifier(op.name(),120));
            if (add || op.dataType()!=null) member.put(group.equals("attributes")?"type":"returnType",text(op.dataType(),160));
            if (add || op.visibility()!=null) {
              String visibility=op.visibility()==null?"-":op.visibility();
              if(!Set.of("+","-","#","~").contains(visibility)) throw invalid(); member.put("visibility",visibility);
            }
            if(group.equals("methods") && (add || op.parameters()!=null)) member.put("parameters",op.parameters()==null?"":bounded(op.parameters(),1000));
          }
        }
        case CREATE_RELATION -> {
          UUID id=allocate(op.ref(),temporary);
          if(op.relationType()==null) throw invalid();
          ObjectNode link=mapper.createObjectNode().put("id",id.toString())
              .put("source",resolve(op.source(),temporary,nodes).toString()).put("destination",resolve(op.destination(),temporary,nodes).toString())
              .put("type",op.relationType().name()).put("name",op.name()==null?"":bounded(op.name().trim(),120));
          boolean structural=Set.of(TipoRelacionUML.ASOCIACION,TipoRelacionUML.AGREGACION,TipoRelacionUML.COMPOSICION).contains(op.relationType());
          link.put("sourceMultiplicity",multiplicity(op.sourceMultiplicity(),structural));
          link.put("targetMultiplicity",multiplicity(op.targetMultiplicity(),structural)); links.put(id,link);
        }
        case UPDATE_RELATION,UPDATE_MULTIPLICITY -> {
          ObjectNode link=links.get(resolve(op.target(),temporary,links));
          if(op.source()!=null) link.put("source",resolve(op.source(),temporary,nodes).toString());
          if(op.destination()!=null) link.put("destination",resolve(op.destination(),temporary,nodes).toString());
          if(op.relationType()!=null) link.put("type",op.relationType().name());
          if(op.name()!=null) link.put("name",bounded(op.name().trim(),120));
          if(op.sourceMultiplicity()!=null || op.type()==OperationType.UPDATE_MULTIPLICITY) link.put("sourceMultiplicity",multiplicity(op.sourceMultiplicity(),true));
          if(op.targetMultiplicity()!=null || op.type()==OperationType.UPDATE_MULTIPLICITY) link.put("targetMultiplicity",multiplicity(op.targetMultiplicity(),true));
        }
        case DELETE_RELATION -> links.remove(resolve(op.target(),temporary,links));
      }
    }
    List<Change> changes=new ArrayList<>();
    diff(originalClasses,nodes,EntityKind.CLASS,changes); diff(originalRelations,links,EntityKind.RELATION,changes);
    if(changes.isEmpty()) throw new BadRequestException("La propuesta no contiene cambios UML efectivos.");
    if(changes.size()>128) throw new BadRequestException("La propuesta afecta demasiados elementos; divide la solicitud.");
    var report=validateProjection(base,nodes,links);
    Preview preview=new Preview(changes,report.issues(),destructive(changes),null);
    if(bytes(preview)>131072) throw new BadRequestException("La propuesta es demasiado grande; divide la solicitud.");
    return preview;
  }

  /** Check the exact persisted patch against the exact current model, then run the existing validators. */
  public void verify(DiagramFullView base, Preview preview) {
    if(preview.changes()==null || preview.changes().isEmpty() || preview.changes().size()>128 || preview.clarification()!=null) throw invalid();
    Map<UUID,ObjectNode> nodes=classes(base),links=relations(base);
    for(Change change:preview.changes()) {
      if(change.id()==null || change.entity()==null || change.kind()==null) throw invalid();
      var target=change.entity()==EntityKind.CLASS?nodes:links;
      JsonNode before=change.before()==null || change.before().isNull()?null:change.before();
      if(!Objects.equals(target.get(change.id()),before)) throw new BadRequestException("La vista previa no coincide con el modelo actual.");
      if(change.kind()==ChangeKind.DELETE) target.remove(change.id());
      else {
        if(change.after()==null || !change.after().isObject() || !change.id().toString().equals(change.after().path("id").asText())) throw invalid();
        target.put(change.id(),change.after().deepCopy());
      }
    }
    validateProjection(base,nodes,links);
  }

  public boolean destructive(List<Change> changes) {
    for(Change change:changes) {
      if(change.kind()==ChangeKind.DELETE) return true;
      if(change.entity()==EntityKind.CLASS && change.kind()==ChangeKind.UPDATE) for(String group:List.of("attributes","methods")) {
        Set<String> ids=new HashSet<>(); change.after().path("properties").path(group).forEach(value->ids.add(value.path("id").asText()));
        for(JsonNode member:change.before().path("properties").path(group)) if(!ids.contains(member.path("id").asText())) return true;
      }
    }
    return false;
  }

  private ValidationReport validateProjection(DiagramFullView base,Map<UUID,ObjectNode> nodes,Map<UUID,ObjectNode> links) {
    DiagramaClase diagram=new DiagramaClase(null,"Vista previa",null);
    Map<UUID,ElementoUML> elements=new LinkedHashMap<>();
    nodes.forEach((id,node)->elements.put(id,new ElementoUML(id,diagram,TipoElementoUML.valueOf(node.path("type").asText()),
        node.path("name").asText(),node.path("x").asDouble(),node.path("y").asDouble(),node.path("properties").toString())));
    List<RelacionUML> relations=new ArrayList<>();
    for(var entry:links.entrySet()) {
      var link=entry.getValue(); var source=elements.get(UUID.fromString(link.path("source").asText()));
      var destination=elements.get(UUID.fromString(link.path("destination").asText()));
      if(source==null || destination==null) throw new BadRequestException("La relación referencia una clase inexistente.");
      relations.add(new RelacionUML(entry.getKey(),diagram,source,destination,link.path("name").asText(),
          TipoRelacionUML.valueOf(link.path("type").asText()),multiplicity(link.path("sourceMultiplicity").asText(),false),multiplicity(link.path("targetMultiplicity").asText(),false)));
    }
    var report=validator.validate(new ArrayList<>(elements.values()),relations);
    var baseline=validation(base);
    Set<String> prior=new HashSet<>(); baseline.issues().stream().filter(issue->issue.severity().equals("ERROR")).forEach(issue->prior.add(issue.elementoId()+":"+issue.mensaje()));
    if(report.issues().stream().anyMatch(issue->issue.severity().equals("ERROR") && !prior.contains(issue.elementoId()+":"+issue.mensaje())))
      throw new BadRequestException("Las operaciones introducen errores de validación UML.");
    return report;
  }
  public ValidationReport validation(DiagramFullView base) {
    DiagramaClase diagram=new DiagramaClase(null,"Validación",null);
    Map<UUID,ElementoUML> nodes=new LinkedHashMap<>();
    for(var e:base.elements()) nodes.put(e.id(),new ElementoUML(e.id(),diagram,e.tipoElemento(),e.nombre(),e.posicionX(),e.posicionY(),e.propiedadesJson()));
    List<RelacionUML> links=base.relations().stream().map(r->new RelacionUML(r.id(),diagram,nodes.get(r.elementoOrigen()),nodes.get(r.elementoDestino()),r.nombreRelacion(),r.tipoRelacion(),r.multiplicidadOrigen(),r.multiplicidadDestino())).toList();
    return validator.validate(new ArrayList<>(nodes.values()),links);
  }
  private Map<UUID,ObjectNode> classes(DiagramFullView base) {
    Map<UUID,ObjectNode> result=new LinkedHashMap<>();
    for(var e:base.elements()) {
      ObjectNode node=mapper.createObjectNode().put("id",e.id().toString()).put("name",e.nombre()).put("type",e.tipoElemento().name()).put("x",e.posicionX()).put("y",e.posicionY());
      try { JsonNode properties=mapper.readTree(e.propiedadesJson()); if(!properties.isObject()) throw invalid(); node.set("properties",properties); }
      catch(Exception invalid) { throw new BadRequestException("La clase contiene propiedades UML no válidas."); }
      result.put(e.id(),node);
    }
    return result;
  }
  private Map<UUID,ObjectNode> relations(DiagramFullView base) {
    Map<UUID,ObjectNode> result=new LinkedHashMap<>();
    for(var r:base.relations()) result.put(r.id(),mapper.createObjectNode().put("id",r.id().toString()).put("name",r.nombreRelacion()==null?"":r.nombreRelacion()).put("type",r.tipoRelacion().name())
        .put("source",r.elementoOrigen().toString()).put("destination",r.elementoDestino().toString()).put("sourceMultiplicity",r.multiplicidadOrigen()==null?"":r.multiplicidadOrigen()).put("targetMultiplicity",r.multiplicidadDestino()==null?"":r.multiplicidadDestino()));
    return result;
  }
  private Map<UUID,ObjectNode> copy(Map<UUID,ObjectNode> source) { Map<UUID,ObjectNode> copy=new LinkedHashMap<>(); source.forEach((key,value)->copy.put(key,value.deepCopy())); return copy; }
  private void diff(Map<UUID,ObjectNode> before,Map<UUID,ObjectNode> after,EntityKind entity,List<Change> result) {
    Set<UUID> ids=new LinkedHashSet<>(before.keySet()); ids.addAll(after.keySet());
    for(UUID id:ids) if(!Objects.equals(before.get(id),after.get(id))) result.add(new Change(!before.containsKey(id)?ChangeKind.CREATE:!after.containsKey(id)?ChangeKind.DELETE:ChangeKind.UPDATE,entity,id,before.get(id),after.get(id)));
  }
  private UUID allocate(String ref,Map<String,UUID> temporary) {
    if(ref==null || !ref.matches("new:[A-Za-z0-9_-]{1,60}") || temporary.containsKey(ref)) throw invalid();
    UUID id=UUID.randomUUID(); temporary.put(ref,id); return id;
  }
  private UUID resolve(String ref,Map<String,UUID> temporary,Map<UUID,?> existing) {
    try { UUID id=temporary.containsKey(ref)?temporary.get(ref):UUID.fromString(ref); if(!existing.containsKey(id)) throw invalid(); return id; }
    catch(Exception invalid) { throw new BadRequestException("La propuesta referencia un elemento UML inexistente o ajeno al diagrama."); }
  }
  private String identifier(String value,int max) { String name=text(value,max); if(!name.matches("[\\p{L}_][\\p{L}\\p{N}_]*")) throw new BadRequestException("Usa identificadores UML válidos en la propuesta."); return name; }
  private String text(String value,int max) { if(value==null || value.isBlank()) throw invalid(); return bounded(value.trim(),max); }
  private String bounded(String value,int max) { if(value.length()>max) throw invalid(); return value; }
  private String multiplicity(String value,boolean required) {
    if(value==null || value.isBlank()) { if(required) throw new BadRequestException("Especifica ambas multiplicidades o solicita una aclaración."); return ""; }
    String result=value.trim(); if(result.length()>20 || !result.matches("\\*|[0-9]+(?:\\.\\.(?:[0-9]+|\\*))?")) throw invalid();
    try { String[] bounds=result.split("\\.\\."); if(!bounds[0].equals("*")) { long lower=Long.parseLong(bounds[0]); if(bounds.length==2 && !bounds[1].equals("*") && lower>Long.parseLong(bounds[1])) throw invalid(); } }
    catch(NumberFormatException invalid) { throw invalid(); } return result;
  }
  private int bytes(Object value) { try { return mapper.writeValueAsBytes(value).length; } catch(Exception failure) { throw invalid(); } }
  private BadRequestException invalid() { return new BadRequestException("La propuesta contiene operaciones o datos UML no válidos."); }
}
