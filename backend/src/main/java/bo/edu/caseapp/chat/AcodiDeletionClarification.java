package bo.edu.caseapp.chat;

import bo.edu.caseapp.ai.UmlProposalIntent;
import bo.edu.caseapp.uml.UmlService;
import java.util.*;
import org.springframework.stereotype.Service;

/** Local ambiguity check, never a mutation or an extra model request. */
@Service
public class AcodiDeletionClarification {
  private final UmlService uml;
  private final com.fasterxml.jackson.databind.ObjectMapper mapper;
  public AcodiDeletionClarification(UmlService uml,com.fasterxml.jackson.databind.ObjectMapper mapper) {this.uml=uml;this.mapper=mapper;}
  public String question(UUID project,UUID diagram,String email,String instruction) {
    String text=UmlProposalIntent.normalize(instruction);
    if(!UmlProposalIntent.deletion(instruction)) return null;
    var model=uml.getDiagram(project,diagram,email);
    if(text.matches(".*\\b(relacion|relaciones|asociacion|asociaciones|conexion|conexiones)\\b.*")) {
      var exact=model.relations().stream().filter(r->text.contains(r.id().toString())).toList();
      if(exact.size()==1) return null;
      var named=model.relations().stream().filter(r->r.nombreRelacion()!=null && !r.nombreRelacion().isBlank() && word(text,UmlProposalIntent.normalize(r.nombreRelacion()))).toList();
      var classes=model.elements().stream().filter(e->word(text,UmlProposalIntent.normalize(e.nombre())) || text.contains(e.id().toString())).map(e->e.id()).toList();
      var pairs=model.relations().stream().filter(r->classes.contains(r.elementoOrigen()) && classes.contains(r.elementoDestino())).toList();
      var matches=(!pairs.isEmpty() && !named.isEmpty())?pairs.stream().filter(named::contains).toList():!pairs.isEmpty()?pairs:named;
      if(matches.size()==1) return null;
      return "¿Qué relación quieres eliminar? Indica su ID o los nombres de sus dos clases y, si hay varias entre ellas, el nombre o ID de la relación. No he preparado ni aplicado ninguna eliminación.";
    }
    if(text.matches(".*\\b(atributo|atributos|metodo|metodos|operacion|operaciones)\\b.*")) {
      String group=text.matches(".*\\b(atributo|atributos)\\b.*")?"attributes":"methods";
      var selected=model.elements().stream().filter(e->word(text,UmlProposalIntent.normalize(e.nombre()))||text.contains(e.id().toString())).toList();
      int matches=0;
      for(var element:selected.isEmpty()?model.elements():selected) {
        try {for(var member:mapper.readTree(element.propiedadesJson()).path(group)) if((!member.path("name").asText().isBlank()&&word(text,UmlProposalIntent.normalize(member.path("name").asText())))||(!member.path("id").asText().isBlank()&&text.contains(member.path("id").asText())))matches++;}
        catch(Exception invalid){ /* Invalid stored members cannot authorize a deletion. */ }
      }
      if(matches!=1)return "Indica el nombre o ID exacto del atributo o método y la clase a la que pertenece. Hay información insuficiente o varias coincidencias; no se ha preparado ni aplicado una eliminación.";
    }
    if(text.matches("^(elimin\\w*|borr\\w*|quit\\w*|suprim\\w*|remov\\w*|remuev\\w*)\\s+(eso|esto|esa|ese|lo|la|todo)[.!? ]*$"))
      return "Indica qué clase, atributo, método o relación deseas eliminar y a qué clase pertenece. No se ha modificado el diagrama.";
    return null;
  }
  private boolean word(String text,String name) { return java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}_])"+java.util.regex.Pattern.quote(name)+"(?![\\p{L}\\p{N}_])").matcher(text).find(); }
}
