package bo.edu.caseapp.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import bo.edu.caseapp.ai.*;
import bo.edu.caseapp.ai.provider.*;
import bo.edu.caseapp.auth.JwtService;
import bo.edu.caseapp.error.*;
import bo.edu.caseapp.project.*;
import bo.edu.caseapp.uml.*;
import bo.edu.caseapp.user.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.config.import=","app.groq.api-key=",
    "app.ai.acodi.poll-ms=3600000","app.ai.proactive.poll-ms=3600000","app.cors.allowed-origins=http://localhost:5173",
    "logging.level.root=WARN","spring.jpa.show-sql=false","debug=false","spring.main.banner-mode=off"})
class AcodiChannelsPostgresIT {
  @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url",()->"jdbc:postgresql://localhost:55439/case_block14_test");r.add("spring.datasource.username",()->"case_block14");r.add("spring.datasource.password",()->"case_block14_test_only");
  }
  @Autowired JdbcTemplate jdbc;@Autowired ObjectMapper mapper;@Autowired ProjectChatService chat;
  @Autowired AcodiStore store;@Autowired AcodiWorker worker;@Autowired AcodiQueue queue;
  @Autowired AiAnalysisStore analysis;@Autowired AiConversationStore conversations;
  @Autowired ProjectRepository projects;@Autowired ProjectMemberRepository members;@Autowired UserRepository users;
  @Autowired DiagramaClaseRepository diagrams;@Autowired UmlService uml;@Autowired JwtService jwt;
  @Autowired TestRestTemplate rest;@Autowired PlatformTransactionManager transactions;
  @MockBean LlmProvider provider;@LocalServerPort int port;
  User owner,editor,outsider;Project project;UUID diagram,general,design;final List<WebSocket> sockets=new ArrayList<>();
  @BeforeEach void setup() {
    assertEquals("case_block14_test",jdbc.queryForObject("SELECT current_database()",String.class));jdbc.execute("TRUNCATE user_account CASCADE");
    owner=users.save(new User("Ana","ana@acodi.test","hash"));editor=users.save(new User("Bruno","bruno@acodi.test","hash"));outsider=users.save(new User("Eva","eva@acodi.test","hash"));
    project=projects.save(new Project(owner,"Ventas","Prueba aislada de ACODI"));members.save(new ProjectMember(project,owner,ProjectRole.PROPIETARIO));members.save(new ProjectMember(project,editor,ProjectRole.EDITOR));
    diagram=diagrams.saveAndFlush(new DiagramaClase(project,"Ventas","Ventas reales del fixture")).getId();
    uml.createElement(project.getId(),diagram,owner.getEmail(),new UmlRequests.SaveElement(null,TipoElementoUML.CLASE,"Venta",999.0,888.0,"{\"attributes\":[{\"id\":\"monto-id\",\"name\":\"monto\",\"type\":\"BigDecimal\"}]}",1L));
    var channels=chat.listChannels(project.getId(),owner.getEmail());general=channels.stream().filter(c->c.slug().equals("general")).findFirst().orElseThrow().id();design=channels.stream().filter(c->c.slug().equals("diseno-uml")).findFirst().orElseThrow().id();
    when(provider.chat(anyList(),anyInt(),any())).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertTrue((Integer)call.getArgument(1)<=600);return LlmResponse.success("La clase Venta tiene un monto BigDecimal. Revisa la multiplicidad con el equipo.",30,40,"mock-groq");});
  }
  @AfterEach void cleanup(){sockets.forEach(WebSocket::abort);sockets.clear();}
  ChatResponses.Message send(String content,User actor) {return chat.send(project.getId(),general,actor.getEmail(),new ChatRequests.SendMessage(UUID.randomUUID(),content,null,diagram)).message();}
  ChatResponses.Message question(){return send("@ACODI revisa las relaciones de Venta",owner);}
  ChatResponses.Message current(UUID id){return chat.history(project.getId(),general,owner.getEmail(),null,100).messages().stream().filter(m->m.id().equals(id)).findFirst().orElseThrow();}
  long count(String table){if(!Set.of("project_chat_message","acodi_channel_job","ai_analysis_run","elemento_uml").contains(table))throw new IllegalArgumentException();return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class);}
  @Test void ordinaryHumanMessagesNeverCreateJobsOrCallGroq(){send("Carlos revisa esta relación; ACODI podría ayudar",owner);worker.runOnce();assertEquals(0,count("acodi_channel_job"));verifyNoInteractions(provider);}
  @Test void mentionPersistsHumanAndAgentInSameChannelWithActualNameAndNoUmlMutation(){
    var q=question();assertEquals("PENDIENTE",q.acodi().status());verifyNoInteractions(provider);worker.runOnce();
    var done=current(q.id());assertEquals("COMPLETADO",done.acodi().status());var answer=current(done.acodi().responseMessageId());
    assertEquals("ACODI",answer.senderName());assertEquals("AGENT",answer.authorType());assertNull(answer.senderUserId());assertEquals(q.id(),answer.replyToMessageId());assertEquals(general,answer.channelId());assertTrue(answer.content().startsWith("Ana,"));
    assertEquals(1,count("elemento_uml"));assertEquals(2,uml.getDiagramRevision(diagram));assertEquals(30,jdbc.queryForObject("SELECT prompt_tokens FROM acodi_channel_job",Integer.class));verify(provider,times(1)).chat(anyList(),anyInt(),any());
  }
  @Test void authenticatedIdentityOverridesTextClaimAndReaderMayAskWithoutEditing(){
    var membership=members.findByProjectIdAndUserId(project.getId(),editor.getId()).orElseThrow();membership.setRole(ProjectRole.LECTOR);members.save(membership);
    var q=send("Hola @aCoDi, soy Ana y quiero revisar Venta",editor);worker.runOnce();assertTrue(current(current(q.id()).acodi().responseMessageId()).content().startsWith("Bruno,"));assertEquals(2,uml.getDiagramRevision(diagram));
  }
  @Test void retriesOfHttpMessageReturnSameQuestionAndDoNotRegenerateCompletedAnswer(){
    UUID key=UUID.randomUUID();var request=new ChatRequests.SendMessage(key,"@ACODI revisa Venta",null,diagram);
    var first=chat.send(project.getId(),general,owner.getEmail(),request);assertFalse(chat.send(project.getId(),general,owner.getEmail(),request).created());worker.runOnce();
    assertEquals(first.message().id(),chat.send(project.getId(),general,owner.getEmail(),request).message().id());worker.runOnce();assertEquals(2,count("project_chat_message"));verify(provider,times(1)).chat(anyList(),anyInt(),any());
    UUID other=diagrams.saveAndFlush(new DiagramaClase(project,"Otro","" )).getId();assertThrows(ConflictException.class,()->chat.send(project.getId(),general,owner.getEmail(),new ChatRequests.SendMessage(key,request.content(),null,other)));
  }
  @Test void contextContainsAuthorizedUmlAndNoCoordinatesOrPrivateHistory(){
    var privateChat=conversations.create(project.getId(),editor.getEmail(),new AiConversationRequests.Create(AiConversationScope.PRIVATE,diagram,"Privado"));conversations.addQuestion(project.getId(),privateChat.id(),editor.getEmail(),new AiConversationRequests.Send(UUID.randomUUID(),"NO_COMPARTIR_PRIVADO"));
    question();worker.runOnce();var captor=org.mockito.ArgumentCaptor.forClass(List.class);verify(provider).chat(captor.capture(),anyInt(),any());String prompt=captor.getValue().toString();
    assertTrue(prompt.contains("Venta"));assertTrue(prompt.contains("BigDecimal"));assertTrue(prompt.contains("Ana"));assertFalse(prompt.contains("NO_COMPARTIR_PRIVADO"));assertFalse(prompt.contains("999.0"));assertFalse(prompt.contains("888.0"));assertFalse(prompt.contains("GROQ_API_KEY"));
  }
  @Test void outsidersForeignDiagramsAndForeignChannelsAreRejected(){
    assertThrows(ForbiddenException.class,()->send("@ACODI revisa",outsider));
    var foreign=projects.save(new Project(outsider,"Ajeno",""));members.save(new ProjectMember(foreign,outsider,ProjectRole.PROPIETARIO));UUID foreignDiagram=diagrams.saveAndFlush(new DiagramaClase(foreign,"Ajeno","")).getId();
    assertThrows(NotFoundException.class,()->chat.send(project.getId(),general,owner.getEmail(),new ChatRequests.SendMessage(UUID.randomUUID(),"@ACODI revisa",null,foreignDiagram)));
    UUID foreignChannel=chat.listChannels(foreign.getId(),outsider.getEmail()).getFirst().id();assertThrows(NotFoundException.class,()->chat.history(project.getId(),foreignChannel,owner.getEmail(),null,50));assertEquals(0,count("acodi_channel_job"));verifyNoInteractions(provider);
  }
  @Test void humanCannotSpoofAgentUsingHttpAndUnauthenticatedRequestsAreDenied() throws Exception {
    String body="{\"clientMessageId\":\""+UUID.randomUUID()+"\",\"content\":\"hola\",\"authorType\":\"AGENT\",\"senderRole\":\"AGENTE\",\"senderUserId\":\""+outsider.getId()+"\"}";
    HttpHeaders headers=new HttpHeaders();headers.setBearerAuth(jwt.createToken(owner.getEmail()));headers.setContentType(MediaType.APPLICATION_JSON);
    var result=rest.exchange(path(),HttpMethod.POST,new HttpEntity<>(body,headers),String.class);assertEquals(HttpStatus.CREATED,result.getStatusCode());var json=mapper.readTree(result.getBody());assertEquals("USER",json.path("authorType").asText());assertEquals(owner.getId().toString(),json.path("senderUserId").asText());
    assertTrue(rest.postForEntity(path(),new HttpEntity<>(body),String.class).getStatusCode().is4xxClientError());verifyNoInteractions(provider);
    String invocation=mapper.writeValueAsString(new ChatRequests.SendMessage(UUID.randomUUID(),"@ACODI revisa Venta",null,diagram));
    var accepted=rest.exchange(path(),HttpMethod.POST,new HttpEntity<>(invocation,headers),String.class);assertEquals(HttpStatus.CREATED,accepted.getStatusCode());
    assertEquals("PENDIENTE",mapper.readTree(accepted.getBody()).path("acodi").path("status").asText());worker.runOnce();
    var history=rest.exchange(path(),HttpMethod.GET,new HttpEntity<>(headers),String.class);assertEquals(HttpStatus.OK,history.getStatusCode());
    boolean found=false;for(var item:mapper.readTree(history.getBody()).path("messages")) if(item.path("authorType").asText().equals("AGENT")){found=true;assertEquals("ACODI",item.path("senderName").asText());assertTrue(item.path("content").asText().startsWith("Ana,"));}assertTrue(found);
  }
  @Test void twoRealSocketsSeeOneCanonicalAnswerOnlyAfterCommitAndRollbackSendsNothing() throws Exception {
    Inbox a=connect(owner,project.getId()),b=connect(editor,project.getId());
    new TransactionTemplate(transactions).executeWithoutResult(tx->{question();tx.setRollbackOnly();});assertNull(a.messages.poll(200,TimeUnit.MILLISECONDS));assertNull(b.messages.poll(200,TimeUnit.MILLISECONDS));assertEquals(0,count("acodi_channel_job"));
    var q=question();worker.runOnce();UUID response=current(q.id()).acodi().responseMessageId();
    for(Inbox inbox:List.of(a,b)){int agents=0;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(System.nanoTime()<until){String event=inbox.messages.poll(100,TimeUnit.MILLISECONDS);if(event==null)continue;var json=mapper.readTree(event);if(json.path("message").path("authorType").asText().equals("AGENT")){agents++;assertEquals(response.toString(),json.path("message").path("id").asText());assertEquals(general.toString(),json.path("channelId").asText());}}assertEquals(1,agents);}
    verify(provider,times(1)).chat(anyList(),anyInt(),any());
  }
  @Test void anotherProjectSocketCannotReceiveInvocationsOrResponses() throws Exception {
    var other=projects.save(new Project(outsider,"Otro",""));members.save(new ProjectMember(other,outsider,ProjectRole.PROPIETARIO));Inbox inbox=connect(outsider,other.getId());question();worker.runOnce();assertNull(inbox.messages.poll(400,TimeUnit.MILLISECONDS));
    assertThrows(CompletionException.class,()->connect(outsider,project.getId()));
  }
  @Test void concurrentWorkersCannotProcessTheSameMessageTwice() throws Exception {
    question();ExecutorService pool=Executors.newFixedThreadPool(2);try{var a=pool.submit(worker::runOnce);var b=pool.submit(worker::runOnce);a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);}finally{pool.shutdownNow();}assertEquals(2,count("project_chat_message"));verify(provider,times(1)).chat(anyList(),anyInt(),any());
  }
  @Test void providerFailurePreservesQuestionAndExplicitRetryIsIdempotent(){
    doReturn(LlmResponse.failure("unavailable",503)).when(provider).chat(anyList(),anyInt(),any());var q=question();worker.runOnce();assertEquals("FALLIDO",current(q.id()).acodi().status());assertEquals(1,count("project_chat_message"));
    jdbc.update("UPDATE acodi_channel_job SET retry_after=now()-interval '1 second'");UUID key=UUID.randomUUID();var retry=new AcodiStore.Retry(key,diagram);store.retry(project.getId(),general,q.id(),owner.getEmail(),retry);store.retry(project.getId(),general,q.id(),owner.getEmail(),retry);
    doReturn(LlmResponse.success("Respuesta recuperada",10,20,"mock")).when(provider).chat(anyList(),anyInt(),any());worker.runOnce();store.retry(project.getId(),general,q.id(),owner.getEmail(),retry);assertEquals("COMPLETADO",current(q.id()).acodi().status());assertEquals(2,count("project_chat_message"));verify(provider,times(2)).chat(anyList(),anyInt(),any());
    assertThrows(NotFoundException.class,()->store.retry(project.getId(),general,q.id(),editor.getEmail(),new AcodiStore.Retry(UUID.randomUUID(),diagram)));
  }
  @Test void rateLimitPausesSharedPolicyAndNoAutomaticProviderRetriesOccur(){
    doReturn(LlmResponse.failure("rate limit",429)).when(provider).chat(anyList(),anyInt(),any());var q=question();worker.runOnce();assertEquals("RATE_LIMITED",current(q.id()).acodi().errorCode());assertTrue(jdbc.queryForObject("SELECT paused_until>now() FROM ai_analysis_policy",Boolean.class));worker.runOnce();verify(provider,times(1)).chat(anyList(),anyInt(),any());
    assertThrows(ConflictException.class,()->store.retry(project.getId(),general,q.id(),owner.getEmail(),new AcodiStore.Retry(UUID.randomUUID(),diagram)));
  }
  @Test void pendingRequestsRecoverButAbandonedProcessingRequiresExplicitRetryWithoutDuplicateCall(){
    var q=question();var work=store.claim().orElseThrow();assertEquals(q.id(),work.messageId());jdbc.update("UPDATE acodi_channel_job SET lease_until=now()-interval '1 second'");worker.runOnce();assertEquals("INTERRUPTED",current(q.id()).acodi().errorCode());verifyNoInteractions(provider);
    store.retry(project.getId(),general,q.id(),owner.getEmail(),new AcodiStore.Retry(UUID.randomUUID(),diagram));worker.runOnce();assertEquals("COMPLETADO",current(q.id()).acodi().status());verify(provider,times(1)).chat(anyList(),anyInt(),any());
  }
  @Test void missingDiagramAsksForContextWithoutModelAndMayBeRetriedWithAuthorizedDiagram(){
    diagrams.saveAndFlush(new DiagramaClase(project,"Otro",""));var q=chat.send(project.getId(),general,owner.getEmail(),new ChatRequests.SendMessage(UUID.randomUUID(),"@ACODI revisa",null)).message();worker.runOnce();assertEquals("CONTEXT_REQUIRED",current(q.id()).acodi().errorCode());verifyNoInteractions(provider);
    jdbc.update("UPDATE acodi_channel_job SET retry_after=now()-interval '1 second'");store.retry(project.getId(),general,q.id(),owner.getEmail(),new AcodiStore.Retry(UUID.randomUUID(),diagram));worker.runOnce();assertEquals("COMPLETADO",current(q.id()).acodi().status());
  }
  @Test void budgetRejectsBeforeProviderAndExplicitRunsShareLedgerWithProactiveAnalysis(){
    analysis.updatePolicy(project.getId(),owner.getEmail(),new AiAnalysisStore.PolicyUpdate(true,8,3600,1000L,500L));var q=question();worker.runOnce();assertEquals("LIMIT_REACHED",current(q.id()).acodi().errorCode());verifyNoInteractions(provider);
    analysis.updatePolicy(project.getId(),owner.getEmail(),new AiAnalysisStore.PolicyUpdate(true,8,3600,10000L,4000L));assertNull(analysis.reserveExplicit(project.getId(),diagram,owner.getEmail(),UUID.randomUUID(),9500,30));
    analysis.requestReview(project.getId(),diagram,owner.getEmail());var job=analysis.claim().orElseThrow();assertFalse(analysis.reserve(job,1000));assertEquals(1,count("ai_analysis_run"));
  }
  @Test void explicitPendingQueriesHavePriorityOverAutomaticBudgetReservations(){
    question();analysis.requestReview(project.getId(),diagram,owner.getEmail());var job=analysis.claim().orElseThrow();assertFalse(analysis.reserve(job,100));worker.runOnce();assertEquals("EXPLICIT",jdbc.queryForObject("SELECT kind FROM ai_analysis_run",String.class));
  }
  @Test void proactivePublicationHasOneDesignChannelProjectionLinkedToHistoricalCanonicalMessage(){
    var publication=conversations.publishRecommendation(project.getId(),diagram,null,owner.getEmail(),"Equipo, revisen la asociación.",10,20,10);
    var messages=chat.history(project.getId(),design,editor.getEmail(),null,50).messages();assertEquals(1,messages.size());assertEquals(publication.messageId(),messages.getFirst().canonicalAiMessageId());assertEquals("ACODI",messages.getFirst().senderName());
    chat.publishRecommendation(project.getId(),owner.getEmail(),"Equipo, revisen la asociación.",publication.messageId());assertEquals(1,chat.history(project.getId(),design,editor.getEmail(),null,50).messages().size());assertEquals(0,chat.history(project.getId(),general,editor.getEmail(),null,50).messages().size());verifyNoInteractions(provider);
  }
  @Test void invalidProviderContentCannotPublishFakeAgentSuccessAndRetriesAreBounded(){
    doReturn(LlmResponse.success(" ",0,0,"mock")).when(provider).chat(anyList(),anyInt(),any());var q=question();
    for(int i=0;i<3;i++){worker.runOnce();assertEquals("FALLIDO",current(q.id()).acodi().status());if(i<2){jdbc.update("UPDATE acodi_channel_job SET retry_after=now()-interval '1 second'");store.retry(project.getId(),general,q.id(),owner.getEmail(),new AcodiStore.Retry(UUID.randomUUID(),diagram));}}
    jdbc.update("UPDATE acodi_channel_job SET retry_after=now()-interval '1 second'");assertThrows(ConflictException.class,()->store.retry(project.getId(),general,q.id(),owner.getEmail(),new AcodiStore.Retry(UUID.randomUUID(),diagram)));assertEquals(1,count("project_chat_message"));verify(provider,times(3)).chat(anyList(),anyInt(),any());
  }
  String path(){return "/api/projects/"+project.getId()+"/chat/channels/"+general+"/messages";}
  Inbox connect(User user,UUID projectId){Inbox inbox=new Inbox();var socket=HttpClient.newHttpClient().newWebSocketBuilder().header("Origin","http://localhost:5173").subprotocols("case-events-v1","bearer."+jwt.createToken(user.getEmail())).buildAsync(URI.create("ws://localhost:"+port+"/ws/projects/"+projectId+"/chat"),inbox).join();sockets.add(socket);return inbox;}
  static class Inbox implements WebSocket.Listener {final BlockingQueue<String> messages=new LinkedBlockingQueue<>();final StringBuilder text=new StringBuilder();@Override public void onOpen(WebSocket ws){ws.request(1);}@Override public CompletionStage<?> onText(WebSocket ws,CharSequence part,boolean last){text.append(part);if(last){messages.add(text.toString());text.setLength(0);}ws.request(1);return null;}}
}
