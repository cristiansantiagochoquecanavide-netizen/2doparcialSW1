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
  @Autowired UmlCanvasProposalState canvas;
  @Autowired bo.edu.caseapp.chat.ProjectChatService channelChat;
  @Autowired bo.edu.caseapp.chat.AcodiWorker channelWorker;
  @Autowired AiMemoryService memory;
  @Autowired AiAnalysisStore analysis;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
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
    assertEquals(editor.getId(),result.decisionBy()); assertNull(result.versionId()); assertEquals(2,count("elemento_uml")); assertEquals(1,count("relacion_uml"));
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
    assertEquals(before,revision()); assertEquals(1,count("elemento_uml")); assertEquals(0,count("relacion_uml")); assertEquals(1,count("version_diagrama"));
    assertEquals(Status.FALLIDA,store.get(project.getId(),diagram,proposal.id(),owner.getEmail()).status()); assertEquals(2,count("ai_proposal_audit"));
    assertEquals("UML_PROPOSAL_CHANGED",mapper.readTree(inbox.messages.poll(2,TimeUnit.SECONDS)).path("type").asText());
    assertNull(inbox.messages.poll(250,TimeUnit.MILLISECONDS)); // No committed UML mutation event.
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
    Inbox inbox=connect(editor,false); var proposal=prepare(owner,conversation.id());
    assertEquals("UML_PROPOSAL_CHANGED",mapper.readTree(inbox.messages.poll(2,TimeUnit.SECONDS)).path("type").asText());
    assertNull(inbox.messages.poll(200,TimeUnit.MILLISECONDS)); // Preview selection only; no UML mutation.
    var history=conversations.history(project.getId(),conversation.id(),editor.getEmail(),null,50);
    assertTrue(history.messages().stream().anyMatch(m->m.senderType()==AiConversationSenderType.AGENT&&m.content().startsWith("Ana,")));
    apply(proposal,editor,false); String event=inbox.messages.poll(2,TimeUnit.SECONDS); assertNotNull(event);
    assertEquals(revision(),mapper.readTree(event).path("revision").asLong()); assertEquals(2,count("elemento_uml"));
    assertEquals("UML_PROPOSAL_CHANGED",mapper.readTree(inbox.messages.poll(2,TimeUnit.SECONDS)).path("type").asText());
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
  @Test void sharedCanvasSelectionPersistsOneActiveAndKeepsOtherProposalsPending() {
    long before=revision(); var a=prepare(owner,null); var b=prepare(editor,null);
    assertEquals(a.id(),canvas.active(project.getId(),diagram,reader.getEmail()));
    canvas.select(project.getId(),diagram,b.id(),editor.getEmail());
    assertEquals(b.id(),canvas.active(project.getId(),diagram,owner.getEmail()));
    assertEquals(b.id(),jdbc.queryForObject("SELECT proposal_id FROM ai_canvas_proposal WHERE diagram_id=?",UUID.class,diagram));
    assertEquals(2,store.list(project.getId(),diagram,null,reader.getEmail()).size());
    assertEquals(before,revision());assertEquals(1,count("elemento_uml"));
    decisions.reject(project.getId(),diagram,b.id(),owner.getEmail());
    assertNull(canvas.active(project.getId(),diagram,reader.getEmail()));
    assertEquals(Status.PENDIENTE,store.get(project.getId(),diagram,a.id(),editor.getEmail()).status());
    assertEquals(before,revision());assertEquals(1,count("elemento_uml"));
    verify(provider,times(2)).chat(anyList(),anyInt(),any());
  }
  @Test void privateProposalNeverBecomesSharedCanvasOrEmitsDiagramSignals() throws Exception {
    var inbox=connect(editor,false);
    var privateChat=conversations.create(project.getId(),owner.getEmail(),new AiConversationRequests.Create(AiConversationScope.PRIVATE,diagram,"Privado"));
    var p=prepare(owner,privateChat.id());
    assertNull(canvas.active(project.getId(),diagram,editor.getEmail()));
    assertThrows(NotFoundException.class,()->canvas.select(project.getId(),diagram,p.id(),owner.getEmail()));
    assertNull(inbox.messages.poll(250,TimeUnit.MILLISECONDS));
  }
  @Test void twoAuthorizedDiagramSocketsReceiveSharedSelectionAndRejectionOnlyAfterCommit() throws Exception {
    var a=prepare(owner,null);var b=prepare(editor,null);
    var first=connect(owner,false);var second=connect(reader,false);
    new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->{canvas.select(project.getId(),diagram,b.id(),editor.getEmail());tx.setRollbackOnly();});
    assertNull(first.messages.poll(250,TimeUnit.MILLISECONDS));assertNull(second.messages.poll(250,TimeUnit.MILLISECONDS));
    canvas.select(project.getId(),diagram,b.id(),editor.getEmail());
    for(var inbox:List.of(first,second))assertEquals("UML_PROPOSAL_CHANGED",mapper.readTree(inbox.messages.poll(3,TimeUnit.SECONDS)).path("type").asText());
    decisions.reject(project.getId(),diagram,b.id(),editor.getEmail());
    for(var inbox:List.of(first,second))assertEquals("UML_PROPOSAL_CHANGED",mapper.readTree(inbox.messages.poll(3,TimeUnit.SECONDS)).path("type").asText());
    assertNull(canvas.active(project.getId(),diagram,reader.getEmail()));assertEquals(Status.PENDIENTE,store.get(project.getId(),diagram,a.id(),owner.getEmail()).status());
  }
  @Test void canvasHttpEnforcesJwtMembershipAndEditorSelection() {
    var p=prepare(owner,null);
    assertTrue(rest.getForEntity(path()+"/canvas",String.class).getStatusCode().is4xxClientError());
    assertEquals(403,post(path()+"/"+p.id()+"/canvas",reader,Map.of()).getStatusCode().value());
    assertEquals(403,post(path()+"/"+p.id()+"/canvas",outsider,Map.of()).getStatusCode().value());
    assertEquals(200,post(path()+"/"+p.id()+"/canvas",editor,Map.of()).getStatusCode().value());
    HttpHeaders headers=new HttpHeaders();headers.setBearerAuth(jwt.createToken(reader.getEmail()));
    var response=rest.exchange(path()+"/canvas",HttpMethod.GET,new HttpEntity<>(headers),String.class);
    assertEquals(200,response.getStatusCode().value());assertTrue(response.getBody().contains(p.id().toString()));
  }
  @Test void canvasApprovalClearsGreenAndStaleHttp409CanRegenerateWithoutDecisionCallsToProvider() {
    var p=prepare(owner,null);addClass("Otra",editor);
    var result=post(path()+"/"+p.id()+"/apply",editor,new UmlProposalExecutor.Apply(UUID.randomUUID(),p.baseRevision(),false));
    assertEquals(409,result.getStatusCode().value());assertEquals(2,count("elemento_uml"));
    assertEquals(Status.CONFLICTO,store.get(project.getId(),diagram,p.id(),owner.getEmail()).status());
    assertEquals(p.id(),canvas.active(project.getId(),diagram,owner.getEmail()));
    var regenerated=proposals.regenerate(project.getId(),diagram,p.id(),editor.getEmail(),UUID.randomUUID()).proposal();
    canvas.select(project.getId(),diagram,regenerated.id(),editor.getEmail());
    apply(regenerated,editor,false);assertNull(canvas.active(project.getId(),diagram,reader.getEmail()));
    verify(provider,times(2)).chat(anyList(),anyInt(),any());
  }
  @Test void acodiChannelMutationPreparesSharedPreviewWithOneGenerationAndNoProvisionalWrites() {
    long before=revision();var channel=channelChat.listChannels(project.getId(),owner.getEmail()).getFirst();
    var request=new bo.edu.caseapp.chat.ChatRequests.SendMessage(UUID.randomUUID(),"@ACODI agrega una clase Pago y relaciona con Venta",null,diagram);
    var human=channelChat.send(project.getId(),channel.id(),owner.getEmail(),request).message();
    channelWorker.runOnce();channelChat.send(project.getId(),channel.id(),owner.getEmail(),request);channelWorker.runOnce();
    var active=canvas.active(project.getId(),diagram,reader.getEmail());assertNotNull(active);
    assertEquals(before,revision());assertEquals(1,count("elemento_uml"));assertEquals(0,count("relacion_uml"));
    var messages=channelChat.history(project.getId(),channel.id(),editor.getEmail(),null,50).messages();
    assertTrue(messages.stream().anyMatch(m->m.authorType().equals("AGENT")&&human.id().equals(m.replyToMessageId())&&m.content().contains(active.toString())));
    apply(store.get(project.getId(),diagram,active,editor.getEmail()),editor,false);
    assertNull(canvas.active(project.getId(),diagram,reader.getEmail()));assertEquals(before+1,revision());
    verify(provider,times(1)).chat(anyList(),anyInt(),any());
  }
  @Test void attributePreviewRejectionAndApprovalPreserveWholeDiagramPositionsAndExistingRelations() throws Exception {
    var producto=uml.createElement(project.getId(),diagram,owner.getEmail(),new UmlRequests.SaveElement(null,TipoElementoUML.CLASE,"Producto",640.0,120.0,"{\"attributes\":[],\"methods\":[]}",revision()));
    var cliente=uml.createElement(project.getId(),diagram,owner.getEmail(),new UmlRequests.SaveElement(null,TipoElementoUML.CLASE,"Cliente",80.0,440.0,"{}",revision()));
    uml.createRelation(project.getId(),diagram,owner.getEmail(),new UmlRequests.SaveRelation(null,venta,producto.id(),"detalle",TipoRelacionUML.ASOCIACION,"1","0..*",revision()));
    uml.createRelation(project.getId(),diagram,owner.getEmail(),new UmlRequests.SaveRelation(null,cliente.id(),venta,"compras",TipoRelacionUML.ASOCIACION,"1","0..*",revision()));
    for(var entry:Map.of(producto.id(),"clase",venta,"metodoPago").entrySet()) {
      authenticate(owner);var base=uml.getDiagram(project.getId(),diagram,owner.getEmail());
      String plan="{\"description\":\"Añadir atributo\",\"operations\":[{\"type\":\"ADD_ATTRIBUTE\",\"target\":\""+entry.getKey()+"\",\"name\":\""+entry.getValue()+"\",\"dataType\":\"String\"}]}";
      doReturn(LlmResponse.success(plan,20,30,"mock")).when(provider).chat(anyList(),anyInt(),any());
      var rejected=proposals.generate(project.getId(),diagram,owner.getEmail(),new UmlProposalStore.Generate(UUID.randomUUID(),"Añadir atributo",null,null)).proposal();
      assertNotNull(rejected);assertEquals(base,uml.getDiagram(project.getId(),diagram,owner.getEmail()));
      decisions.reject(project.getId(),diagram,rejected.id(),editor.getEmail());
      assertEquals(base,uml.getDiagram(project.getId(),diagram,owner.getEmail()));
      var accepted=proposals.generate(project.getId(),diagram,owner.getEmail(),new UmlProposalStore.Generate(UUID.randomUUID(),"Añadir atributo",null,null)).proposal();
      apply(accepted,editor,false);var result=uml.getDiagram(project.getId(),diagram,owner.getEmail());
      assertEquals(base.elements().size(),result.elements().size());assertEquals(base.relations(),result.relations());
      for(var original:base.elements()) {
        var actual=result.elements().stream().filter(e->e.id().equals(original.id())).findFirst().orElseThrow();
        assertEquals(original.posicionX(),actual.posicionX());assertEquals(original.posicionY(),actual.posicionY());
        if(!original.id().equals(entry.getKey())) assertEquals(original,actual);
        else assertTrue(mapper.readTree(actual.propiedadesJson()).path("attributes").toString().contains(entry.getValue()));
      }
    }
    verify(provider,times(4)).chat(anyList(),anyInt(),any());
  }
  @Test void newRelationProposalPreservesAllExistingClassesAndRelationsAfterApprovalAndRejection() {
    var producto=uml.createElement(project.getId(),diagram,owner.getEmail(),new UmlRequests.SaveElement(null,TipoElementoUML.CLASE,"Producto",640.0,120.0,"{}",revision()));
    var cliente=uml.createElement(project.getId(),diagram,owner.getEmail(),new UmlRequests.SaveElement(null,TipoElementoUML.CLASE,"Cliente",80.0,440.0,"{}",revision()));
    uml.createRelation(project.getId(),diagram,owner.getEmail(),new UmlRequests.SaveRelation(null,cliente.id(),venta,"compras",TipoRelacionUML.ASOCIACION,"1","0..*",revision()));
    var base=uml.getDiagram(project.getId(),diagram,owner.getEmail());
    String plan="{\"description\":\"Relacionar Venta y Producto\",\"operations\":[{\"type\":\"CREATE_RELATION\",\"ref\":\"new:detalle\",\"source\":\""+venta+"\",\"destination\":\""+producto.id()+"\",\"relationType\":\"ASOCIACION\",\"name\":\"detalle\",\"sourceMultiplicity\":\"1\",\"targetMultiplicity\":\"0..*\"}]}";
    doReturn(LlmResponse.success(plan,20,30,"mock")).when(provider).chat(anyList(),anyInt(),any());
    var rejected=proposals.generate(project.getId(),diagram,owner.getEmail(),new UmlProposalStore.Generate(UUID.randomUUID(),"Relacionar",null,null)).proposal();
    assertEquals(base,uml.getDiagram(project.getId(),diagram,owner.getEmail()));decisions.reject(project.getId(),diagram,rejected.id(),editor.getEmail());
    assertEquals(base,uml.getDiagram(project.getId(),diagram,owner.getEmail()));
    var accepted=proposals.generate(project.getId(),diagram,owner.getEmail(),new UmlProposalStore.Generate(UUID.randomUUID(),"Relacionar",null,null)).proposal();
    apply(accepted,editor,false);var result=uml.getDiagram(project.getId(),diagram,owner.getEmail());
    assertEquals(base.elements(),result.elements());assertEquals(base.relations().size()+1,result.relations().size());
    assertTrue(result.relations().containsAll(base.relations()));verify(provider,times(2)).chat(anyList(),anyInt(),any());
  }
  @Test void naturalRelationDeletionFromChannelIsSharedCanBeRejectedThenOnlyTargetIsDeleted() throws Exception {
    var producto=uml.createElement(project.getId(),diagram,owner.getEmail(),new UmlRequests.SaveElement(null,TipoElementoUML.CLASE,"Producto",600.0,100.0,"{}",revision()));
    var cliente=uml.createElement(project.getId(),diagram,owner.getEmail(),new UmlRequests.SaveElement(null,TipoElementoUML.CLASE,"Cliente",50.0,400.0,"{}",revision()));
    var target=uml.createRelation(project.getId(),diagram,owner.getEmail(),new UmlRequests.SaveRelation(null,venta,producto.id(),"detalle",TipoRelacionUML.ASOCIACION,"1","0..*",revision()));
    var remaining=uml.createRelation(project.getId(),diagram,owner.getEmail(),new UmlRequests.SaveRelation(null,cliente.id(),venta,"compras",TipoRelacionUML.ASOCIACION,"1","0..*",revision()));
    var base=uml.getDiagram(project.getId(),diagram,owner.getEmail());var one=connect(owner,false);var two=connect(editor,false);
    doReturn(LlmResponse.success("{\"description\":\"Eliminar detalle\",\"operations\":[{\"type\":\"DELETE_RELATION\",\"target\":\""+target.id()+"\"}]}",20,30,"mock")).when(provider).chat(anyList(),anyInt(),any());
    var response=channelCommand("@ACODI ¿Puedes eliminar la relación entre Venta y Producto?",owner);
    assertFalse(response.content().contains("Herramientas"));assertEquals(base,uml.getDiagram(project.getId(),diagram,owner.getEmail()));
    var active=canvas.active(project.getId(),diagram,editor.getEmail());assertNotNull(active);
    for(var inbox:List.of(one,two))assertEquals("UML_PROPOSAL_CHANGED",mapper.readTree(inbox.messages.poll(3,TimeUnit.SECONDS)).path("type").asText());
    decisions.reject(project.getId(),diagram,active,editor.getEmail());assertEquals(base,uml.getDiagram(project.getId(),diagram,owner.getEmail()));
    for(var inbox:List.of(one,two))assertEquals("UML_PROPOSAL_CHANGED",mapper.readTree(inbox.messages.poll(3,TimeUnit.SECONDS)).path("type").asText());
    channelCommand("@acodi quiero que quites la relación detalle",owner);
    active=canvas.active(project.getId(),diagram,editor.getEmail());var pending=store.get(project.getId(),diagram,active,editor.getEmail());
    assertEquals(1,pending.preview().changes().size());assertEquals(target.id(),pending.preview().changes().getFirst().id());
    apply(pending,editor,true);var after=uml.getDiagram(project.getId(),diagram,owner.getEmail());
    assertEquals(base.elements(),after.elements());assertEquals(List.of(remaining),after.relations());assertNull(canvas.active(project.getId(),diagram,reader.getEmail()));
    for(var inbox:List.of(one,two)) { // Preparation, confirmed UML batch, then decision: both see the same events.
      assertEquals("UML_PROPOSAL_CHANGED",mapper.readTree(inbox.messages.poll(3,TimeUnit.SECONDS)).path("type").asText());
      assertEquals("PROPOSAL_APPLIED",mapper.readTree(inbox.messages.poll(3,TimeUnit.SECONDS)).path("type").asText());
      assertEquals("UML_PROPOSAL_CHANGED",mapper.readTree(inbox.messages.poll(3,TimeUnit.SECONDS)).path("type").asText());
    }
    verify(provider,times(2)).chat(anyList(),anyInt(),any());
  }
  @Test void ambiguousDeletionAsksInSameChannelWithoutProviderOrProposal() {
    var answer=channelCommand("@ACODI podrías quitar la relación",owner);
    assertTrue(answer.content().contains("¿Qué relación"));assertEquals(0,count("ai_uml_proposal"));assertEquals(1,count("elemento_uml"));verifyNoInteractions(provider);
  }
  @Test void memoryChangesRequireAuthorConfirmationAreIdempotentAndCanBeCancelled() {
    var channel=channelChat.listChannels(project.getId(),owner.getEmail()).getFirst();
    var human=channelChat.send(project.getId(),channel.id(),owner.getEmail(),new bo.edu.caseapp.chat.ChatRequests.SendMessage(UUID.randomUUID(),"@ACODI guardar contexto: Sistema de ventas",null,diagram)).message();channelWorker.runOnce();
    assertNull(memory.get(project.getId(),diagram,owner.getEmail()).projectMemory().systemDescription());
    assertTrue(channelCommand("@ACODI confirmar "+human.id(),editor).content().contains("No hay una confirmación tuya"));
    assertNull(memory.get(project.getId(),diagram,owner.getEmail()).projectMemory().systemDescription());
    channelCommand("@ACODI confirmar "+human.id(),owner);assertEquals("Sistema de ventas",memory.get(project.getId(),diagram,owner.getEmail()).projectMemory().systemDescription());
    channelCommand("@ACODI confirmar "+human.id(),owner);assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM project_ai_memory_audit",Integer.class));
    var cancel=channelChat.send(project.getId(),channel.id(),owner.getEmail(),new bo.edu.caseapp.chat.ChatRequests.SendMessage(UUID.randomUUID(),"@ACODI guardar regla: No vender sin stock",null,diagram)).message();channelWorker.runOnce();
    channelCommand("@ACODI cancelar "+cancel.id(),owner);assertTrue(memory.get(project.getId(),diagram,owner.getEmail()).entries().isEmpty());
    assertTrue(channelCommand("@ACODI contexto",owner).content().contains("Sistema de ventas"));assertEquals(0,count("ai_uml_proposal"));verifyNoInteractions(provider);
  }
  @Test void confirmedMemoryEntryIsCreatedOnlyOnceAndInferenceNeedsExplicitConfirmation() {
    var channel=channelChat.listChannels(project.getId(),owner.getEmail()).getFirst();
    var human=channelChat.send(project.getId(),channel.id(),owner.getEmail(),new bo.edu.caseapp.chat.ChatRequests.SendMessage(UUID.randomUUID(),"@ACODI guardar regla: El pedido requiere un cliente",null,diagram)).message();channelWorker.runOnce();
    channelCommand("@ACODI confirmar "+human.id(),owner);channelCommand("@ACODI confirmar "+human.id(),owner);
    var entries=memory.get(project.getId(),diagram,owner.getEmail()).entries();assertEquals(1,entries.size());assertEquals(AiMemoryConfidence.CONFIRMED,entries.getFirst().confidenceScope());
    assertTrue(channelCommand("@ACODI ver entrada "+entries.getFirst().id(),owner).content().contains("El pedido requiere un cliente"));
    memory.recordAnalysisInference(project.getId(),diagram,owner.getEmail(),"Hipótesis de comercio");
    var inferred=memory.get(project.getId(),diagram,owner.getEmail()).entries().stream().filter(e->e.confidenceScope()==AiMemoryConfidence.INFERRED).findFirst().orElseThrow();
    var pending=channelCommand("@ACODI confirmar hipótesis "+inferred.id(),owner);
    assertTrue(memory.get(project.getId(),diagram,owner.getEmail()).entries().stream().anyMatch(e->e.id().equals(inferred.id())&&e.confidenceScope()==AiMemoryConfidence.INFERRED));
    channelCommand("@ACODI confirmar "+pending.replyToMessageId(),owner);
    assertTrue(memory.get(project.getId(),diagram,owner.getEmail()).entries().stream().anyMatch(e->e.id().equals(inferred.id())&&e.confidenceScope()==AiMemoryConfidence.CONFIRMED));verifyNoInteractions(provider);
  }
  @Test void analysisAnswersWithinChannelAndDoesNotGenerateAnEditingProposal() {
    doReturn(LlmResponse.success("Recomiendo revisar la multiplicidad entre clases según las reglas confirmadas.",20,30,"mock")).when(provider).chat(anyList(),anyInt(),any());
    var answer=channelCommand("@ACODI analiza el diagrama",owner);assertEquals("AGENT",answer.authorType());assertTrue(answer.content().startsWith("Ana,"));assertTrue(answer.content().contains("Recomiendo"));assertEquals(0,count("ai_uml_proposal"));verify(provider,times(1)).chat(anyList(),anyInt(),any());
  }
  @Test void analysisPolicyIsChangedFromChatOnlyAfterConfirmationWithNoProviderCall() {
    analysis.updatePolicy(project.getId(),owner.getEmail(),new AiAnalysisStore.PolicyUpdate(false,null,null,null,null));
    var channel=channelChat.listChannels(project.getId(),owner.getEmail()).getFirst();
    var question=channelChat.send(project.getId(),channel.id(),owner.getEmail(),new bo.edu.caseapp.chat.ChatRequests.SendMessage(UUID.randomUUID(),"@ACODI activar análisis automático",null,diagram)).message();channelWorker.runOnce();
    assertFalse(jdbc.queryForObject("SELECT automatic_enabled FROM ai_analysis_policy WHERE project_id=?",Boolean.class,project.getId()));
    channelCommand("@ACODI confirmar "+question.id(),owner);
    assertTrue(jdbc.queryForObject("SELECT automatic_enabled FROM ai_analysis_policy WHERE project_id=?",Boolean.class,project.getId()));
    assertTrue(channelCommand("@ACODI estado del análisis",reader).content().contains("activado"));verifyNoInteractions(provider);
  }
  @Test void contextConfirmationCannotOverwriteConcurrentInformationAndReadersCannotChangeIt() {
    var channel=channelChat.listChannels(project.getId(),owner.getEmail()).getFirst();
    var question=channelChat.send(project.getId(),channel.id(),owner.getEmail(),new bo.edu.caseapp.chat.ChatRequests.SendMessage(UUID.randomUUID(),"@ACODI guardar contexto: Información anterior",null,diagram)).message();channelWorker.runOnce();
    memory.updateProjectMemory(project.getId(),editor.getEmail(),new AiMemoryRequests.UpdateProjectMemory("Ventas","Contexto nuevo de Bruno",null,null,null));
    assertTrue(channelCommand("@ACODI confirmar "+question.id(),owner).content().contains("cambió"));
    assertEquals("Contexto nuevo de Bruno",memory.get(project.getId(),diagram,owner.getEmail()).projectMemory().systemDescription());
    var denied=channelChat.send(project.getId(),channel.id(),reader.getEmail(),new bo.edu.caseapp.chat.ChatRequests.SendMessage(UUID.randomUUID(),"@ACODI guardar contexto: Suplantación",null,diagram)).message();channelWorker.runOnce();
    assertEquals("FALLIDO",channelChat.history(project.getId(),channel.id(),reader.getEmail(),null,50).messages().stream().filter(m->m.id().equals(denied.id())).findFirst().orElseThrow().acodi().status());
    assertEquals("Contexto nuevo de Bruno",memory.get(project.getId(),diagram,owner.getEmail()).projectMemory().systemDescription());verifyNoInteractions(provider);
  }
  @Test void privateChatKeepsMemoryPrivateAndReturnsAuthorizedProposalReferenceWithoutGroupPublication() throws Exception {
    var conversation=conversations.create(project.getId(),owner.getEmail(),new AiConversationRequests.Create(AiConversationScope.PRIVATE,diagram,"Privado"));
    var inbox=connect(editor,true);var signal=connect(editor,false);
    var local=chat.send(project.getId(),conversation.id(),owner.getEmail(),new AiConversationRequests.Send(UUID.randomUUID(),"@ACODI guardar contexto: SECRETO_PRIVADO"));
    assertTrue(local.answer().content().contains("No se guardó"));assertNull(memory.get(project.getId(),diagram,owner.getEmail()).projectMemory().systemDescription());verifyNoInteractions(provider);
    var turn=chat.send(project.getId(),conversation.id(),owner.getEmail(),new AiConversationRequests.Send(UUID.randomUUID(),"@ACODI puedes agregar una clase Pago"));assertNotNull(turn.proposalId());
    assertEquals("PRIVATE",store.get(project.getId(),diagram,turn.proposalId(),owner.getEmail()).scope());assertNull(canvas.active(project.getId(),diagram,editor.getEmail()));
    assertThrows(NotFoundException.class,()->store.get(project.getId(),diagram,turn.proposalId(),editor.getEmail()));
    assertNull(inbox.messages.poll(250,TimeUnit.MILLISECONDS));assertNull(signal.messages.poll(250,TimeUnit.MILLISECONDS));verify(provider,times(1)).chat(anyList(),anyInt(),any());
    var shown=chat.send(project.getId(),conversation.id(),owner.getEmail(),new AiConversationRequests.Send(UUID.randomUUID(),"@ACODI ver propuesta "+turn.proposalId()));assertEquals(turn.proposalId(),shown.proposalId());verify(provider,times(1)).chat(anyList(),anyInt(),any());
  }
  private bo.edu.caseapp.chat.ChatResponses.Message channelCommand(String content,User author) {
    var channel=channelChat.listChannels(project.getId(),author.getEmail()).getFirst();
    var question=channelChat.send(project.getId(),channel.id(),author.getEmail(),new bo.edu.caseapp.chat.ChatRequests.SendMessage(UUID.randomUUID(),content,null,diagram)).message();channelWorker.runOnce();
    return channelChat.history(project.getId(),channel.id(),author.getEmail(),null,50).messages().stream().filter(m->question.id().equals(m.replyToMessageId())).findFirst().orElseThrow();
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
