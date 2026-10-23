package bo.edu.caseapp.chat;

import static org.junit.jupiter.api.Assertions.*;
import bo.edu.caseapp.project.*;
import bo.edu.caseapp.uml.*;
import bo.edu.caseapp.user.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Explicit opt-in: exactly one provider request, only synthetic data, never part of repeatable regression. */
@EnabledIfSystemProperty(named="acodi.live",matches="true")
@SpringBootTest(properties={"app.ai.acodi.poll-ms=3600000","app.ai.proactive.poll-ms=3600000","app.ai.acodi.max-output-tokens=256","logging.level.root=WARN","spring.jpa.show-sql=false","debug=false"})
class AcodiRealGroqIT {
  @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->"jdbc:postgresql://localhost:55439/case_block14_test");r.add("spring.datasource.username",()->"case_block14");r.add("spring.datasource.password",()->"case_block14_test_only");}
  @Autowired Environment environment;@Autowired JdbcTemplate jdbc;@Autowired UserRepository users;
  @Autowired ProjectRepository projects;@Autowired ProjectMemberRepository members;@Autowired DiagramaClaseRepository diagrams;
  @Autowired ProjectChatService chat;@Autowired AcodiWorker worker;@Autowired AcodiQueue queue;@Autowired UmlService uml;
  @Test void oneRealProviderCallForSyntheticAuthorizedDiagram(){
    Assumptions.assumeTrue(!environment.getProperty("app.groq.api-key","").isBlank(),"GROQ_API_KEY no configurada");
    assertEquals("case_block14_test",jdbc.queryForObject("SELECT current_database()",String.class));
    var user=users.save(new User("Prueba","acodi-live-"+UUID.randomUUID()+"@example.test","unused"));
    var project=projects.save(new Project(user,"Prueba sintética ACODI","Sin datos reales de colaboradores"));
    members.save(new ProjectMember(project,user,ProjectRole.PROPIETARIO));var diagram=diagrams.saveAndFlush(new DiagramaClase(project,"Prueba sintética","")).getId();
    uml.createElement(project.getId(),diagram,user.getEmail(),new UmlRequests.SaveElement(null,TipoElementoUML.CLASE,"Venta",0.0,0.0,"{}",1L));
    var channel=chat.listChannels(project.getId(),user.getEmail()).getFirst();
    var question=chat.send(project.getId(),channel.id(),user.getEmail(),new ChatRequests.SendMessage(UUID.randomUUID(),"@ACODI describe en una frase la clase Venta del diagrama.",null,diagram)).message();
    worker.runOnce();var result=queue.view(question.id());
    var status=jdbc.queryForObject("SELECT http_status FROM ai_analysis_run WHERE project_id=? AND kind='EXPLICIT'",Integer.class,project.getId());
    System.out.println("ACODI_REAL_GROQ_RESULT status="+result.status()+" http="+status);
    assertEquals("COMPLETADO",result.status(),"La comprobación real no obtuvo respuesta; HTTP="+status+", estado="+result.errorCode());
    assertNotNull(result.responseMessageId());
  }
}
