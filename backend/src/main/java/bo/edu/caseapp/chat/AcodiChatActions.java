package bo.edu.caseapp.chat;

import bo.edu.caseapp.ai.*;
import bo.edu.caseapp.error.*;
import bo.edu.caseapp.project.ProjectService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicit chat commands reuse existing services. Durable confirmations never apply UML. */
@Service @Transactional
public class AcodiChatActions {
  private final JdbcTemplate jdbc;private final ProjectService projects;private final AiMemoryService memory;
  private final AiAnalysisStore analysis;private final UmlProposalStore proposals;private final UmlCanvasProposalState canvas;
  private final ObjectMapper mapper;private final Validator validator;
  public AcodiChatActions(JdbcTemplate jdbc,ProjectService projects,AiMemoryService memory,AiAnalysisStore analysis,UmlProposalStore proposals,UmlCanvasProposalState canvas,ObjectMapper mapper,Validator validator) {
    this.jdbc=jdbc;this.projects=projects;this.memory=memory;this.analysis=analysis;this.proposals=proposals;this.canvas=canvas;this.mapper=mapper;this.validator=validator;
  }
  public Optional<String> handle(AcodiStore.Work work,String question) {
    projects.requireAccess(work.projectId(),work.email());
    // The same accepted human message can never execute a local action twice, including after restart.
    jdbc.queryForObject("SELECT id FROM project_chat_message WHERE id=? FOR UPDATE",UUID.class,work.messageId());
    var prior=jdbc.queryForList("SELECT result_text FROM acodi_chat_action WHERE message_id=?",String.class,work.messageId());
    if(!prior.isEmpty())return Optional.of(prior.getFirst());
    String text=UmlProposalIntent.normalize(question),answer=null;
    if(text.matches("(ayuda|comandos|que puedes hacer)[.!? ]*")) answer=help();
    else if(text.matches("(ver |mostrar |consultar )?(contexto( del proyecto)?|memoria( del proyecto)?)( parte [1-9])?[.!? ]*")) {
      var part=Pattern.compile(".* parte ([1-9]).*").matcher(text);answer=page(describeMemory(work),part.matches()?Integer.parseInt(part.group(1)):1,"contexto");
    }
    else if(text.matches("(ver |listar |mostrar )?propuestas( anteriores| pendientes)?[.!? ]*")) answer=describeProposals(work);
    else if(text.matches("(ver |mostrar )?(recomendaciones|estado del analisis|configuracion del analisis)[.!? ]*")) answer=describeAnalysis(work);
    else if(text.matches("(?:(?:solicitar|programar|hacer) )?revision (global|del diagrama)[.!? ]*")) {
      if(work.diagramId()==null)answer="Abre un diagrama para solicitar la revisión global.";
      else {analysis.requestReview(work.projectId(),work.diagramId(),work.email());answer="Revisión global programada con los límites del proyecto. Una recomendación relevante se publicará en #diseño-uml sin duplicados.";}
    }
    else {
      var confirmation=Pattern.compile("^(confirmar|confirma|cancelar|cancela)(?: (?:memoria|contexto|cambio))? ([0-9a-f-]{36})[.!? ]*$").matcher(text);
      var view=Pattern.compile("^(?:ver|mostrar|activar) propuesta ([0-9a-f-]{36})[.!? ]*$").matcher(text);
      var entryView=Pattern.compile("^(?:ver|mostrar) entrada ([0-9a-f-]{36})(?: parte ([1-9]))?[.!? ]*$").matcher(text);
      if(confirmation.matches()) answer=confirm(work,UUID.fromString(confirmation.group(2)),confirmation.group(1).startsWith("confirm"));
      else if(entryView.matches()) {var entry=findEntry(work,UUID.fromString(entryView.group(1)));answer=page(entry.confidenceScope()+" · "+entry.entryType()+"\n"+entry.content(),entryView.group(2)==null?1:Integer.parseInt(entryView.group(2)),"ver entrada "+entry.id());}
      else if(view.matches()) {
        UUID id=UUID.fromString(view.group(1));var p=proposals.get(work.projectId(),work.diagramId(),id,work.email());
        if(!p.scope().equals("GROUP"))throw new NotFoundException("Propuesta compartida no encontrada.");
        if(p.status()==UmlProposalContract.Status.PENDIENTE || p.status()==UmlProposalContract.Status.CONFLICTO)canvas.select(work.projectId(),work.diagramId(),id,work.email());
        answer="Propuesta "+id+" · "+p.status()+" · "+p.description()+". "+(p.status()==UmlProposalContract.Status.PENDIENTE?"La vista previa está en el lienzo; decide con Aprobar o Rechazar.":"Las propuestas no se aplican desde una respuesta del chat.");
      } else {
        Pending pending=parse(work,question,text);
        if(pending!=null) {
          projects.requireCanEdit(work.projectId(),work.email());
          answer="Cambio de información compartida pendiente: "+question+"\nNo se ha guardado todavía. Para confirmarlo escribe @ACODI confirmar "+work.messageId()+"; para descartarlo, @ACODI cancelar "+work.messageId()+". Solo tú puedes confirmar esta solicitud en este canal.";
          save(work.messageId(),pending.kind(),json(pending),"PENDING",answer);return Optional.of(limit(answer));
        }
      }
    }
    if(answer==null)return Optional.empty();
    answer=limit(answer);save(work.messageId(),"RESPONSE","{}","DONE",answer);return Optional.of(answer);
  }
  private Pending parse(AcodiStore.Work w,String original,String text) {
    int colon=original.indexOf(':');String value=colon<0?"":original.substring(colon+1).trim();
    String prefix=text.contains(":")?text.substring(0,text.indexOf(':')).trim():text;
    Map<String,String> fields=Map.of("contexto","systemDescription","descripcion","systemDescription","nombre del sistema","systemName","dominio","businessDomain","objetivos","objectives","informacion adicional","additionalContext");
    String field=prefix.replaceFirst("^(guardar|guarda|definir|define|actualizar|actualiza|establecer|establece|limpiar) (?:el |la )?", "");
    if(!field.equals(prefix)&&fields.containsKey(field)) {
      if(value.isEmpty()&&!prefix.startsWith("limpiar "))throw new BadRequestException("Escribe el contenido después de dos puntos.");
      int max=field.equals("nombre del sistema")?160:field.equals("dominio")?1000:4000;
      if(value.length()>max)throw new BadRequestException("El contenido excede el límite permitido.");
      return new Pending("MEMORY_FIELD",fields.get(field),value,null,json(memory.get(w.projectId(),w.diagramId(),w.email()).projectMemory()));
    }
    var entries=Map.of("regla",AiMemoryEntryType.BUSINESS_RULE,"regla de negocio",AiMemoryEntryType.BUSINESS_RULE,"restriccion",AiMemoryEntryType.FUNCTIONAL_CONSTRAINT,"decision",AiMemoryEntryType.ARCHITECTURE_DECISION,"convencion",AiMemoryEntryType.MODELING_CONVENTION,"informacion",AiMemoryEntryType.ADDITIONAL);
    String entry=prefix.replaceFirst("^(guardar|guarda|agregar|agrega|registrar|registra) (?:una? |la |el )?", "");
    if(!entry.equals(prefix)&&entries.containsKey(entry)) {
      if(value.isBlank()||value.length()>4000)throw new BadRequestException("Escribe el contenido de la entrada después de dos puntos (hasta 4.000 caracteres).");
      return new Pending("ENTRY_ADD",entries.get(entry).name(),value,null,null);
    }
    var edit=Pattern.compile("^(editar|actualizar|confirmar|eliminar|borrar) (?:entrada|hipotesis|regla|contexto) ([0-9a-f-]{36})$").matcher(prefix);
    if(edit.matches()) {
      UUID id=UUID.fromString(edit.group(2));var existing=findEntry(w,id);
      boolean delete=Set.of("eliminar","borrar").contains(edit.group(1));
      String content=value.isBlank()?existing.content():value;
      if(content.length()>4000)throw new BadRequestException("La entrada supera 4.000 caracteres.");
      return new Pending(delete?"ENTRY_DELETE":"ENTRY_UPDATE",existing.entryType().name(),content,id,json(existing));
    }
    if(text.matches("(?:activar|activa|habilitar|habilita|desactivar|desactiva) (?:el )?analisis automatico[.!? ]*"))
      return new Pending("POLICY","enabled",String.valueOf(!text.startsWith("desactiv")),null,null);
    if(prefix.equals("configurar analisis")||prefix.equals("configura analisis")) {
      String policy=text.substring(text.indexOf(':')+1).trim();
      validatePolicy(policy);return new Pending("POLICY","limits",policy,null,null);
    }
    var disposition=Pattern.compile("^(resolver|resuelta|descartar) recomendacion ([0-9a-f-]{36})[.!? ]*$").matcher(text);
    if(disposition.matches())return new Pending("DISPOSITION",disposition.group(1).equals("descartar")?"DISMISSED":"RESOLVED","",UUID.fromString(disposition.group(2)),null);
    return null;
  }
  private String confirm(AcodiStore.Work w,UUID id,boolean approve) {
    projects.requireCanEdit(w.projectId(),w.email());
    var rows=jdbc.queryForList("SELECT a.*,m.sender_user_id FROM acodi_chat_action a JOIN project_chat_message m ON m.id=a.message_id JOIN project_chat_channel c ON c.id=m.channel_id JOIN user_account u ON u.id=m.sender_user_id WHERE a.message_id=? AND c.project_id=? AND m.channel_id=? AND u.email=? FOR UPDATE OF a",id,w.projectId(),w.channelId(),w.email());
    if(rows.isEmpty())return "No hay una confirmación tuya con ese ID en este canal. Una confirmación de memoria no aprueba operaciones UML.";
    var row=rows.getFirst();if(!row.get("status").equals("PENDING"))return "Esta solicitud ya fue atendida. No se repitió ningún cambio.";
    if(!approve) {jdbc.update("UPDATE acodi_chat_action SET status='CANCELLED',updated_at=now() WHERE message_id=?",id);return "Solicitud cancelada. La información compartida permanece intacta.";}
    Pending p;try{p=mapper.readValue((String)row.get("payload_json"),Pending.class);}catch(Exception e){throw new BadRequestException("Solicitud de confirmación no válida.");}
    // Serialize shared-context confirmations without blocking UML inserts' foreign-key checks.
    jdbc.queryForObject("SELECT id FROM project WHERE id=? FOR NO KEY UPDATE",UUID.class,w.projectId());
    if(p.kind().equals("MEMORY_FIELD")) {
      var old=memory.get(w.projectId(),w.diagramId(),w.email()).projectMemory();
      if(!json(old).equals(p.expected()))return "El contexto cambió desde que lo solicitaste. Vuelve a enviar el cambio para revisarlo; no se sobrescribió información.";
      Map<String,String> data=new HashMap<>();data.put("systemName",old.systemName());data.put("systemDescription",old.systemDescription());data.put("businessDomain",old.businessDomain());data.put("objectives",old.objectives());data.put("additionalContext",old.additionalContext());data.put(p.field(),p.content());
      var request=new AiMemoryRequests.UpdateProjectMemory(data.get("systemName"),data.get("systemDescription"),data.get("businessDomain"),data.get("objectives"),data.get("additionalContext"));
      check(request);memory.updateProjectMemory(w.projectId(),w.email(),request);
    } else if(p.kind().equals("ENTRY_ADD")) memory.createConfirmedEntry(w.projectId(),w.email(),new AiMemoryRequests.SaveEntry(AiMemoryEntryType.valueOf(p.field()),null,p.content()));
    else if(p.kind().startsWith("ENTRY_")) {
      var existing=findEntry(w,p.target());
      if(!json(existing).equals(p.expected()))return "La entrada cambió. Solicita de nuevo la actualización; no se sobrescribió información.";
      if(p.kind().equals("ENTRY_DELETE"))memory.deleteEntry(w.projectId(),p.target(),w.email());
      else memory.updateAsConfirmed(w.projectId(),p.target(),w.email(),new AiMemoryRequests.SaveEntry(existing.entryType(),existing.diagramId(),p.content()));
    } else if(p.kind().equals("POLICY")) analysis.updatePolicy(w.projectId(),w.email(),p.field().equals("enabled")?new AiAnalysisStore.PolicyUpdate(Boolean.valueOf(p.content()),null,null,null,null):validatePolicy(p.content()));
    else if(p.kind().equals("DISPOSITION"))analysis.disposition(w.projectId(),w.diagramId(),p.target(),w.email(),p.field());
    jdbc.update("UPDATE acodi_chat_action SET status='CONFIRMED',updated_at=now() WHERE message_id=?",id);
    return "Cambio confirmado y guardado. El diagrama UML no se ha modificado.";
  }
  private AiAnalysisStore.PolicyUpdate validatePolicy(String text) {
    Map<String,Long> values=new HashMap<>();
    for(String part:text.split("[,; ]+")){if(part.isBlank())continue;var pair=part.split("=",2);if(pair.length!=2||!Set.of("presupuesto","reserva","llamadas","periodo").contains(pair[0]))throw new BadRequestException("Usa presupuesto=100000 reserva=50000 llamadas=5 periodo=3600.");try{values.put(pair[0],Long.valueOf(pair[1]));}catch(NumberFormatException e){throw new BadRequestException("Los límites deben ser enteros.");}}
    if(values.isEmpty())throw new BadRequestException("Indica al menos un límite.");
    var request=new AiAnalysisStore.PolicyUpdate(null,values.containsKey("llamadas")?Math.toIntExact(values.get("llamadas")):null,values.containsKey("periodo")?Math.toIntExact(values.get("periodo")):null,values.get("presupuesto"),values.get("reserva"));check(request);return request;
  }
  private AiMemoryResponses.Entry findEntry(AcodiStore.Work w,UUID id) {return memory.get(w.projectId(),w.diagramId(),w.email()).entries().stream().filter(e->e.id().equals(id)).findFirst().orElseThrow(()->new NotFoundException("Entrada de memoria no encontrada en el contexto autorizado."));}
  private String describeMemory(AcodiStore.Work w) {
    var view=memory.get(w.projectId(),w.diagramId(),w.email());var m=view.projectMemory();StringBuilder s=new StringBuilder("Contexto compartido confirmado:\n");
    s.append("Nombre: ").append(m.systemName()).append("\nDescripción: ").append(m.systemDescription()).append("\nDominio: ").append(m.businessDomain()).append("\nObjetivos: ").append(m.objectives()).append("\nInformación adicional: ").append(m.additionalContext());
    for(var e:view.entries())s.append("\n").append(e.id()).append(" · ").append(e.confidenceScope()).append(" · ").append(e.entryType()).append(": ").append(e.content());return s.toString();
  }
  private String describeProposals(AcodiStore.Work w) {
    if(w.diagramId()==null)return "Abre un diagrama para consultar sus propuestas. Los historiales se conservan.";
    StringBuilder s=new StringBuilder("Propuestas compartidas (las privadas se conservan aisladas):");
    for(var p:proposals.list(w.projectId(),w.diagramId(),null,w.email()))if(p.scope().equals("GROUP"))s.append("\n").append(p.id()).append(" · ").append(p.status()).append(" · ").append(p.description());
    return s+"\nUsa @ACODI ver propuesta ID para activar una pendiente en el lienzo.";
  }
  private String describeAnalysis(AcodiStore.Work w) {
    if(w.diagramId()==null)return "Abre un diagrama para consultar el análisis y sus recomendaciones.";
    var data=analysis.status(w.projectId(),w.diagramId(),w.email());var s=new StringBuilder("Estado del análisis y recomendaciones:\n");
    var policy=(Map<?,?>)data.get("policy");s.append("Automático: ").append(Boolean.TRUE.equals(policy.get("automatic_enabled"))?"activado":"desactivado").append("\nPresupuesto compartido: ").append(policy.get("token_budget")).append("; reserva explícita: ").append(policy.get("explicit_reserve")).append("\nMáximo de análisis automáticos: ").append(policy.get("max_calls")).append(" por ").append(policy.get("period_seconds")).append(" segundos.");
    for(var item:(List<?>)data.get("recommendations")){var r=(Map<?,?>)item;var details=(com.fasterxml.jackson.databind.JsonNode)r.get("details");s.append("\n").append(r.get("id")).append(" · ").append(r.get("status")).append(" · ").append(details==null?"Recomendación":details.path("title").asText()).append(": ").append(details==null?"":details.path("recommendedChange").asText());}return s.toString();
  }
  private void save(UUID id,String kind,String payload,String status,String result) {jdbc.update("INSERT INTO acodi_chat_action(message_id,kind,payload_json,status,result_text) VALUES (?,?,?,?,?)",id,kind,payload,status,limit(result));}
  private String page(String text,int page,String command){int count=Math.max(1,(text.length()+2999)/3000);if(page>count)return "No hay esa parte; hay "+count+" partes.";String part=text.substring((page-1)*3000,Math.min(text.length(),page*3000));return part+"\nParte "+page+"/"+count+(page<count?". Continúa con @ACODI "+command+" parte "+(page+1):"");}
  private void check(Object value){if(!validator.validate(value).isEmpty())throw new BadRequestException("Los valores no cumplen los límites permitidos.");}
  private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new BadRequestException("No se pudo preparar la confirmación.");}}
  private String limit(String value){return value.length()>3700?value.substring(0,3600)+"\n… Consulta una entrada o propuesta por su ID para ver más detalle.":value;}
  private String help(){return "Puedes pedir crear, modificar o eliminar clases, atributos, métodos y relaciones; se previsualizan en verde y se aprueban en el lienzo.\nAnálisis: @ACODI analiza el diagrama; recomendaciones; estado del análisis.\nMemoria: contexto; guardar contexto: …; definir nombre del sistema: …; definir dominio: …; definir objetivos: …; guardar regla: …; guardar restricción: …; guardar decisión: …; guardar convención: …; editar entrada ID: …; confirmar hipótesis ID; eliminar entrada ID. Los cambios compartidos requieren confirmar ID o cancelar ID.\nConfiguración: activar/desactivar análisis automático; configurar análisis: presupuesto=100000 reserva=50000 llamadas=5 periodo=3600.\nRecomendaciones: resolver recomendación ID; descartar recomendación ID; preparar recomendación ID.\nPropuestas anteriores: propuestas; ver propuesta ID. Privacidad: abre Conversaciones de ACODI en la navegación del chat y elige Privadas; nunca se incorporan automáticamente a la memoria compartida.";}
  public record Pending(String kind,String field,String content,UUID target,String expected) { }
}
