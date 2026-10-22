package bo.edu.caseapp.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static bo.edu.caseapp.ai.UmlProposalContract.*;
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
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Actual PostgreSQL, HTTP JWT and WebSocket. The provider is mocked; no live Groq request. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.config.import=", "app.groq.api-key=", "app.ai.proactive.poll-ms=3600000",
    "app.cors.allowed-origins=http://localhost:5173", "logging.level.root=WARN",
    "spring.jpa.show-sql=false", "debug=false", "spring.main.banner-mode=off"})
class UmlProposalPostgresIT {
  @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url",()->"jdbc:postgresql://localhost:55439/case_block14_test");
    r.add("spring.datasource.username",()->"case_block14"); r.add("spring.datasource.password",()->"case_block14_test_only");
  }
  @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper mapper;
  @Autowired UmlProposalService proposals; @Autowired UmlProposalStore store;
  @Autowired UmlProposalApplication application; @Autowired UmlProposalDecisions decisions;
  @Autowired AiConversationStore conversations; @Autowired AiConversationService chat;
  @Autowired ProjectRepository projects; @Autowired ProjectMemberRepository members;
  @Autowired UserRepository users; @Autowired DiagramaClaseRepository diagrams;
  @Autowired JwtService jwt; @Autowired TestRestTemplate rest;
  @SpyBean UmlService uml; @MockBean LlmProvider provider;
  @LocalServerPort int port;
  User owner,editor,reader,outsider; Project project; UUID diagram,venta,attribute;
  final List<WebSocket> sockets=new ArrayList<>();
  @BeforeEach void setup() {
    assertEquals("case_block14_test",jdbc.queryForObject("SELECT current_database()",String.class));
    jdbc.execute("TRUNCATE user_account CASCADE");
    owner=users.save(new User("Ana","ana@block15.test","hash")); editor=users.save(new User("Bruno","bruno@block15.test","hash"));
    reader=users.save(new User("Clara","clara@block15.test","hash")); outsider=users.save(new User("Eva","eva@block15.test","hash"));
    project=projects.save(new Project(owner,"Ventas","Prueba aislada"));
    members.save(new ProjectMember(project,owner,ProjectRole.PROPIETARIO)); members.save(new ProjectMember(project,editor,ProjectRole.EDITOR)); members.save(new ProjectMember(project,reader,ProjectRole.LECTOR));
    diagram=diagrams.saveAndFlush(new DiagramaClase(project,"Ventas","Prueba")).getId(); authenticate(owner);
    attribute=UUID.randomUUID();
    venta=uml.createElement(project.getId(),diagram,owner.getEmail(),new UmlRequests.SaveElement(null,TipoElementoUML.CLASE,"Venta",12.0,20.0,
        "{\"attributes\":[{\"id\":\""+attribute+"\",\"name\":\"precio\",\"type\":\"Double\",\"visibility\":\"-\"}],\"methods\":[]}",revision())).id();
    when(provider.chat(anyList(),anyInt(),any())).thenAnswer(call->{
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"External provider must run outside a transaction");
      assertTrue((Integer)call.getArgument(1)<=1800); return LlmResponse.success(plan(),40,80,"mock-groq");
    });
  }
  @AfterEach void cleanup() { sockets.forEach(WebSocket::abort); sockets.clear(); SecurityContextHolder.clearContext(); }
  String plan() { return """
      {"description":"Pago vinculado con Venta","operations":[
        {"type":"CREATE_CLASS","ref":"new:pago","name":"Pago"},
        {"type":"ADD_ATTRIBUTE","target":"new:pago","name":"monto","dataType":"BigDecimal"},
        {"type":"UPDATE_ATTRIBUTE","target":"%s","memberId":"%s","dataType":"BigDecimal"},
        {"type":"CREATE_RELATION","ref":"new:r","source":"%s","destination":"new:pago","relationType":"ASOCIACION","sourceMultiplicity":"1","targetMultiplicity":"0..*"}]}
      """.formatted(venta,attribute,venta); }
  UmlProposalStore.View prepare(User user,UUID conversation) {
    var result=proposals.generate(project.getId(),diagram,user.getEmail(),new UmlProposalStore.Generate(UUID.randomUUID(),"Agrega Pago y cambia precio a BigDecimal",conversation,null));
    assertEquals("READY",result.status(),result.errorCode()); assertNotNull(result.proposal()); return result.proposal();
  }
  UmlProposalStore.View apply(UmlProposalStore.View proposal,User user,boolean confirmed) {
    authenticate(user); return application.apply(project.getId(),diagram,proposal.id(),user.getEmail(),new UmlProposalExecutor.Apply(UUID.randomUUID(),proposal.baseRevision(),confirmed));
  }
  @Test void generatesWithoutMutationAndAppliesAnExactAtomicBatchWithSingleRevisionAndVersion() throws Exception {
    long before=revision(); var proposal=prepare(owner,null);
    assertEquals(before,revision()); assertEquals(1,count("elemento_uml")); assertEquals(3,proposal.preview().changes().size());
    var result=apply(proposal,editor,false); assertEquals(Status.APLICADA,result.status()); assertEquals(before+1,revision());
    assertEquals(editor.getId(),result.decisionBy()); assertNotNull(result.versionId()); assertEquals(2,count("elemento_uml")); assertEquals(1,count("relacion_uml"));
    var model=uml.getDiagram(project.getId(),diagram,owner.getEmail());
    var updated=model.elements().stream().filter(e->e.id().equals(venta)).findFirst().orElseThrow();
    assertEquals("BigDecimal",mapper.readTree(updated.propiedadesJson()).path("attributes").get(0).path("type").asText());
    assertEquals(12.0,updated.posicionX()); assertEquals("0..*",model.relations().getFirst().multiplicidadDestino());
    assertEquals(1,count("version_diagrama")); assertEquals(2,count("ai_proposal_audit"));
    assertEquals(proposal.id(),apply(proposal,owner,false).id()); assertEquals(before+1,revision()); assertEquals(2,count("elemento_uml"));
    verify(provider,times(1)).chat(anyList(),anyInt(),any());
  }
  @Test void duplicateGenerationAndReadingPreviewNeverCallProviderAgain() {
    UUID key=UUID.randomUUID(); var request=new UmlProposalStore.Generate(key,"Agrega Pago",null,null);
    var first=proposals.generate(project.getId(),diagram,owner.getEmail(),request);
    var second=proposals.generate(project.getId(),diagram,owner.getEmail(),request);
    assertEquals("READY",first.status()); assertEquals(first.proposal().id(),second.proposal().id());
    store.list(project.getId(),diagram,null,editor.getEmail()); store.get(project.getId(),diagram,first.proposal().id(),reader.getEmail());
    assertThrows(ConflictException.class,()->proposals.generate(project.getId(),diagram,owner.getEmail(),new UmlProposalStore.Generate(key,"Otra instrucción",null,null)));
    verify(provider,times(1)).chat(anyList(),anyInt(),any());
  }
  @Test void malformedAndUnsupportedResponsesProduceSafeErrorsAndNoUmlWrites() {
    for(String source:List.of("{","{\"description\":\"Ataque\",\"operations\":[{\"type\":\"EXECUTE_SQL\"}]}",
        "{\"description\":\"Ajeno\",\"operations\":[{\"type\":\"DELETE_CLASS\",\"target\":\""+UUID.randomUUID()+"\"}]}")) {
      doReturn(LlmResponse.success(source,1,1,"mock")).when(provider).chat(anyList(),anyInt(),any());
      var result=proposals.generate(project.getId(),diagram,owner.getEmail(),new UmlProposalStore.Generate(UUID.randomUUID(),"Cambio inválido",null,null));
      assertEquals("ERROR",result.status()); assertNull(result.proposal());
    }
    assertEquals(0,count("ai_uml_proposal")); assertEquals(1,count("elemento_uml")); assertEquals(2,revision());
  }
  @Test void httpPermissionsJwtAndForeignProjectIsolationAreEnforced() {
    var proposal=prepare(owner,null); String url=path()+"/"+proposal.id()+"/apply";
    assertEquals(HttpStatus.FORBIDDEN,post(url,reader,new UmlProposalExecutor.Apply(UUID.randomUUID(),proposal.baseRevision(),false)).getStatusCode());
    assertEquals(HttpStatus.FORBIDDEN,post(url,outsider,new UmlProposalExecutor.Apply(UUID.randomUUID(),proposal.baseRevision(),false)).getStatusCode());
    assertTrue(rest.postForEntity(url,new UmlProposalExecutor.Apply(UUID.randomUUID(),proposal.baseRevision(),false),String.class).getStatusCode().is4xxClientError());
    var other=projects.save(new Project(outsider,"Ajeno",""));
    members.save(new ProjectMember(other,outsider,ProjectRole.PROPIETARIO));
    assertThrows(NotFoundException.class,()->store.get(other.getId(),diagram,proposal.id(),outsider.getEmail()));
    assertEquals(Status.PENDIENTE,store.get(project.getId(),diagram,proposal.id(),owner.getEmail()).status());
  }
  @Test void staleProposalReturns409ThenRegenerationUsesNewRevision() {
    var old=prepare(owner,null); addClass("Factura",editor);
    assertEquals(HttpStatus.CONFLICT,post(path()+"/"+old.id()+"/apply",owner,new UmlProposalExecutor.Apply(UUID.randomUUID(),old.baseRevision(),false)).getStatusCode());
    assertEquals(Status.CONFLICTO,store.get(project.getId(),diagram,old.id(),owner.getEmail()).status()); assertEquals(2,count("elemento_uml"));
    var fresh=proposals.regenerate(project.getId(),diagram,old.id(),owner.getEmail(),UUID.randomUUID()).proposal();
    assertNotNull(fresh); assertEquals(revision(),fresh.baseRevision()); assertEquals(Status.APLICADA,apply(fresh,owner,false).status()); assertEquals(3,count("elemento_uml"));
  }
  @Test void failureAfterClassWritesRollsBackEveryClassRelationRevisionVersionAndUmlEvent() throws Exception {
    var proposal=prepare(owner,null); long before=revision(); Inbox inbox=connect(editor,false);
    doAnswer(call->{
      assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
      assertEquals(2,count("elemento_uml"),"Class writes exist inside the uncommitted batch before the injected failure");
      assertEquals(before+1,revision());
      throw new IllegalStateException("Injected failure after class writes");
    }).when(uml).createRelation(eq(project.getId()),eq(diagram),anyString(),any(UmlRequests.SaveRelation.class));
    assertThrows(BadRequestException.class,()->apply(proposal,owner,false));
    assertEquals(before,revision()); assertEquals(1,count("elemento_uml")); assertEquals(0,count("relacion_uml")); assertEquals(0,count("version_diagrama"));
    assertEquals(Status.FALLIDA,store.get(project.getId(),diagram,proposal.id(),owner.getEmail()).status()); assertEquals(2,count("ai_proposal_audit"));
    assertNull(inbox.messages.poll(500,TimeUnit.MILLISECONDS));
    var current=uml.getDiagram(project.getId(),diagram,owner.getEmail()).elements().getFirst(); assertTrue(current.propiedadesJson().contains("Double"));
    // The batch marker is cleared even on exception; subsequent normal writes work.
    addClass("Factura",owner); assertEquals(before+1,revision());
  }
  @Test void rejectIsAuditedAndIdempotentWithoutUmlOrProviderWork() {
    var proposal=prepare(owner,null); long before=revision();
    assertEquals(Status.RECHAZADA,decisions.reject(project.getId(),diagram,proposal.id(),editor.getEmail()).status());
    decisions.reject(project.getId(),diagram,proposal.id(),owner.getEmail());
    assertEquals(2,count("ai_proposal_audit")); assertEquals(before,revision());
    assertThrows(ConflictException.class,()->apply(proposal,owner,false)); verify(provider,times(1)).chat(anyList(),anyInt(),any());
  }
  @Test void destructiveMemberDeletionRequiresSeparateExplicitConfirmation() {
    doReturn(LlmResponse.success("{\"description\":\"Eliminar precio\",\"operations\":[{\"type\":\"DELETE_ATTRIBUTE\",\"target\":\""+venta+"\",\"memberId\":\""+attribute+"\"}]}",1,1,"mock")).when(provider).chat(anyList(),anyInt(),any());
    var proposal=prepare(owner,null); assertTrue(proposal.preview().destructive());
    assertThrows(UmlProposalExecutor.ConfirmationRequired.class,()->apply(proposal,owner,false));
    assertEquals(Status.PENDIENTE,store.get(project.getId(),diagram,proposal.id(),owner.getEmail()).status());
    assertEquals(Status.APLICADA,apply(proposal,owner,true).status());
  }
  @Test void privateProposalsRemainHiddenFromOtherEditorsAndProjectOwnerIncludingWebsocket() throws Exception {
    var conversation=conversations.create(project.getId(),editor.getEmail(),new AiConversationRequests.Create(AiConversationScope.PRIVATE,diagram,"Privado"));
    Inbox groupInbox=connect(owner,true), umlInbox=connect(owner,false);
    var proposal=prepare(editor,conversation.id()); assertEquals("PRIVATE",proposal.scope());
    assertTrue(store.list(project.getId(),diagram,null,owner.getEmail()).isEmpty());
    assertThrows(NotFoundException.class,()->store.get(project.getId(),diagram,proposal.id(),owner.getEmail()));
    assertThrows(NotFoundException.class,()->apply(proposal,owner,false));
    assertNull(groupInbox.messages.poll(400,TimeUnit.MILLISECONDS)); assertNull(umlInbox.messages.poll(100,TimeUnit.MILLISECONDS));
    apply(proposal,editor,false);
    String event=umlInbox.messages.poll(2,TimeUnit.SECONDS); assertNotNull(event); assertEquals("PROPOSAL_APPLIED",mapper.readTree(event).path("type").asText());
    assertFalse(event.contains("Privado")); assertFalse(event.contains("BigDecimal")); assertNull(groupInbox.messages.poll(400,TimeUnit.MILLISECONDS));
    assertEquals(2,conversations.history(project.getId(),conversation.id(),editor.getEmail(),null,50).messages().stream().filter(m->m.senderType()==AiConversationSenderType.AGENT).count());
  }
  @Test void groupProposalAnswersUseAuthenticatedNameAndPublishOnlyAfterCommit() throws Exception {
    var conversation=conversations.create(project.getId(),owner.getEmail(),new AiConversationRequests.Create(AiConversationScope.GROUP,diagram,"Equipo"));
    Inbox inbox=connect(editor,false); var proposal=prepare(owner,conversation.id()); assertNull(inbox.messages.poll(200,TimeUnit.MILLISECONDS));
    var history=conversations.history(project.getId(),conversation.id(),editor.getEmail(),null,50);
    assertTrue(history.messages().stream().anyMatch(m->m.senderType()==AiConversationSenderType.AGENT&&m.content().startsWith("Ana,")));
    apply(proposal,editor,false); String event=inbox.messages.poll(2,TimeUnit.SECONDS); assertNotNull(event);
    assertEquals(revision(),mapper.readTree(event).path("revision").asLong()); assertEquals(2,count("elemento_uml"));
    assertNull(inbox.messages.poll(250,TimeUnit.MILLISECONDS));
  }
  @Test void chatCreatesProposalButSimpleApprovalCannotExecuteOrCallGroq() {
    var conversation=conversations.create(project.getId(),owner.getEmail(),new AiConversationRequests.Create(AiConversationScope.GROUP,diagram,"Equipo"));
    var turn=chat.send(project.getId(),conversation.id(),owner.getEmail(),new AiConversationRequests.Send(UUID.randomUUID(),"IA, agrega una clase Pago"));
    assertNotNull(turn.answer()); assertEquals(1,count("ai_uml_proposal")); assertEquals(1,count("elemento_uml"));
    var approval=chat.send(project.getId(),conversation.id(),owner.getEmail(),new AiConversationRequests.Send(UUID.randomUUID(),"Sí, aplica la propuesta"));
    assertTrue(approval.answer().content().contains("vista previa")); assertEquals(1,count("elemento_uml")); verify(provider,times(1)).chat(anyList(),anyInt(),any());
  }
  @Test void lockOwnedByAnotherCollaboratorPreventsApplyUntilReleased() {
    var proposal=prepare(owner,null); uml.acquireLock(project.getId(),diagram,editor.getEmail());
    assertThrows(ForbiddenException.class,()->apply(proposal,owner,false));
    assertEquals(Status.PENDIENTE,store.get(project.getId(),diagram,proposal.id(),owner.getEmail()).status());
    uml.releaseLock(project.getId(),diagram,editor.getEmail()); assertEquals(Status.APLICADA,apply(proposal,owner,false).status());
  }
  @Test void twoUsersApplyingDifferentProposalsBasedOnSameRevisionCannotBothCommit() throws Exception {
    var first=prepare(owner,null); var second=prepare(editor,null);
    ExecutorService pool=Executors.newFixedThreadPool(2); CountDownLatch start=new CountDownLatch(1);
    try {
      Callable<Boolean> a=()->{start.await();try {apply(first,owner,false);return true;}catch(ConflictException conflict){return false;}finally{SecurityContextHolder.clearContext();}};
      Callable<Boolean> b=()->{start.await();try {apply(second,editor,false);return true;}catch(ConflictException conflict){return false;}finally{SecurityContextHolder.clearContext();}};
      var fa=pool.submit(a); var fb=pool.submit(b); start.countDown(); assertNotEquals(fa.get(10,TimeUnit.SECONDS),fb.get(10,TimeUnit.SECONDS));
      assertEquals(2,count("elemento_uml")); assertEquals(3,revision()); assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM ai_uml_proposal WHERE status='CONFLICTO'",Integer.class));
    } finally {pool.shutdownNow();}
  }
  @Test void concurrentReplayOfSameProposalReturnsSameResultWithoutDuplicateWrites() throws Exception {
    var proposal=prepare(owner,null); ExecutorService pool=Executors.newFixedThreadPool(2);
    try {
      var a=pool.submit(()->apply(proposal,owner,false)); var b=pool.submit(()->apply(proposal,editor,false));
      assertEquals(a.get(10,TimeUnit.SECONDS).appliedRevision(),b.get(10,TimeUnit.SECONDS).appliedRevision());
      assertEquals(2,count("elemento_uml")); assertEquals(1,count("version_diagrama")); assertEquals(2,count("ai_proposal_audit"));
    } finally {pool.shutdownNow();}
  }
  @Test void promptsUseOnlyAuthorizedContextNeverOtherPrivateConversations() {
    var privateConversation=conversations.create(project.getId(),editor.getEmail(),new AiConversationRequests.Create(AiConversationScope.PRIVATE,diagram,"Secreto"));
    conversations.addQuestion(project.getId(),privateConversation.id(),editor.getEmail(),new AiConversationRequests.Send(UUID.randomUUID(),"SECRETO_PRIVADO_NO_ENVIAR"));
    prepare(owner,null);
    var captor=org.mockito.ArgumentCaptor.forClass(List.class); verify(provider).chat(captor.capture(),anyInt(),any());
    String prompt=captor.getValue().toString(); assertFalse(prompt.contains("SECRETO_PRIVADO_NO_ENVIAR")); assertFalse(prompt.contains("GROQ_API_KEY")); assertTrue(prompt.contains(attribute.toString()));
  }
  @Test void providerFailurePersistsQuestionAndTechnicalRetryCannotDuplicateGeneration() {
    doReturn(LlmResponse.failure("service unavailable",503)).when(provider).chat(anyList(),anyInt(),any());
    var conversation=conversations.create(project.getId(),owner.getEmail(),new AiConversationRequests.Create(AiConversationScope.GROUP,diagram,"Equipo"));
    var turn=chat.send(project.getId(),conversation.id(),owner.getEmail(),new AiConversationRequests.Send(UUID.randomUUID(),"Agrega una clase Pago"));
    assertEquals(AiConversationMessageStatus.ERROR,turn.question().status()); assertNull(turn.answer());
    var retried=chat.retry(project.getId(),conversation.id(),turn.question().id(),owner.getEmail());
    assertEquals(AiConversationMessageStatus.ERROR,retried.question().status()); assertEquals(1,count("elemento_uml")); assertEquals(0,count("ai_uml_proposal"));
    UUID key=UUID.randomUUID();
    var abandoned=store.begin(project.getId(),diagram,owner.getEmail(),new UmlProposalStore.Generate(key,"Agrega Factura",conversation.id(),null),null);
    jdbc.update("UPDATE ai_proposal_request SET lease_until=now()-interval '1 second' WHERE id=?",abandoned.id());
    assertEquals("ERROR",store.generation(project.getId(),diagram,abandoned.id(),owner.getEmail()).status());
    assertTrue(conversations.history(project.getId(),conversation.id(),owner.getEmail(),null,50).messages().stream()
        .anyMatch(m->key.equals(m.clientMessageId())&&m.status()==AiConversationMessageStatus.ERROR));
    verify(provider,times(1)).chat(anyList(),anyInt(),any());
  }
  @Test void selectedRecommendationUsesCurrentModelAndRetainsItsOriginNotice() {
    var conversation=conversations.create(project.getId(),owner.getEmail(),new AiConversationRequests.Create(AiConversationScope.GROUP,diagram,"Recomendaciones"));
    var published=conversations.publishRecommendation(project.getId(),diagram,conversation.id(),owner.getEmail(),"Equipo, revisar pagos",0,0,0);
    UUID recommendation=UUID.randomUUID();
    jdbc.update("INSERT INTO ai_recommendation(id,project_id,diagram_id,revision,semantic_hash,dedup_key,evidence_hash,details_json,conversation_id,message_id) VALUES (?,?,?,?,?,?,?,?,?,?)",
        recommendation,project.getId(),diagram,revision(),"semantic","dedup","evidence","{\"recommendedChange\":\"Agregar Pago\"}",conversation.id(),published.messageId());
    addClass("Factura",editor);
    var generated=proposals.generate(project.getId(),diagram,owner.getEmail(),new UmlProposalStore.Generate(UUID.randomUUID(),null,null,recommendation));
    assertEquals("READY",generated.status()); assertEquals(revision(),generated.proposal().baseRevision()); assertEquals(recommendation,generated.proposal().recommendationId());
    assertTrue(generated.proposal().preview().warnings().stream().anyMatch(w->w.mensaje().contains("modelo actual")));
    assertEquals(2,count("elemento_uml"));
  }
  private long revision() {return uml.getDiagramRevision(diagram);}
  private void authenticate(User user) {SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user.getEmail(),"unused",List.of()));}
  private void addClass(String name,User user) {authenticate(user);uml.createElement(project.getId(),diagram,user.getEmail(),new UmlRequests.SaveElement(null,TipoElementoUML.CLASE,name,0.0,0.0,"{}",revision()));}
  private int count(String table) {if(!Set.of("elemento_uml","relacion_uml","version_diagrama","ai_uml_proposal","ai_proposal_audit").contains(table)) throw new IllegalArgumentException();return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class);}
  private String path() {return "/api/projects/"+project.getId()+"/diagrams/"+diagram+"/ai/proposals";}
  private ResponseEntity<String> post(String path,User user,Object body) {HttpHeaders headers=new HttpHeaders();headers.setBearerAuth(jwt.createToken(user.getEmail()));return rest.exchange(path,HttpMethod.POST,new HttpEntity<>(body,headers),String.class);}
  private Inbox connect(User user,boolean group) {
    Inbox inbox=new Inbox();String path=group?"/ws/projects/"+project.getId()+"/chat":"/ws/diagrams/"+diagram;
    var socket=HttpClient.newHttpClient().newWebSocketBuilder().header("Origin","http://localhost:5173").subprotocols("case-events-v1","bearer."+jwt.createToken(user.getEmail()))
        .buildAsync(URI.create("ws://localhost:"+port+path),inbox).join();sockets.add(socket);return inbox;
  }
  static class Inbox implements WebSocket.Listener {
    final BlockingQueue<String> messages=new LinkedBlockingQueue<>();final StringBuilder partial=new StringBuilder();
    @Override public void onOpen(WebSocket socket){socket.request(1);}
    @Override public CompletionStage<?> onText(WebSocket socket,CharSequence text,boolean last){partial.append(text);if(last){messages.add(partial.toString());partial.setLength(0);}socket.request(1);return null;}
  }
}
