package bo.edu.caseapp.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import bo.edu.caseapp.ai.provider.*;
import bo.edu.caseapp.auth.JwtService;
import bo.edu.caseapp.error.*;
import bo.edu.caseapp.interop.XmiService;
import bo.edu.caseapp.project.*;
import bo.edu.caseapp.uml.*;
import bo.edu.caseapp.user.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real isolated PostgreSQL and HTTP/WebSocket; Groq is mocked, never an external network call. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.config.import=", "app.groq.api-key=", "app.ai.proactive.poll-ms=3600000",
    "app.ai.proactive.enabled=true", "app.ai.proactive.debounce-seconds=1",
    "app.cors.allowed-origins=http://localhost:5173", "logging.level.root=WARN",
    "logging.level.bo.edu.caseapp=INFO", "spring.main.banner-mode=off", "debug=false",
    "logging.level.org.springframework=WARN", "logging.level.org.hibernate=WARN", "spring.jpa.show-sql=false" })
class AiAnalysisPostgresIT {
  @DynamicPropertySource static void db(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> System.getProperty("block14.db.url", "jdbc:postgresql://localhost:55439/case_block14_test"));
    registry.add("spring.datasource.username", () -> "case_block14");
    registry.add("spring.datasource.password", () -> "case_block14_test_only");
  }
  @Autowired JdbcTemplate jdbc;
  @Autowired UmlService uml;
  @Autowired AiAnalysisStore store;
  @Autowired AiAnalysisWorker worker;
  @Autowired AiOrchestrationService orchestration;
  @Autowired AiAnalysisResult resultValidator;
  @Autowired AiAnalysisProperties analysisConfig;
  @Autowired AiAssistantService assistant;
  @Autowired bo.edu.caseapp.versioning.VersionService versions;
  @Autowired AiSemanticSnapshot snapshots;
  @Autowired AiMemoryService memory;
  @Autowired AiConversationStore conversations;
  @Autowired ProjectRepository projects;
  @Autowired ProjectMemberRepository members;
  @Autowired UserRepository users;
  @Autowired DiagramaClaseRepository diagrams;
  @Autowired XmiService imports;
  @Autowired JwtService jwt;
  @Autowired ObjectMapper mapper;
  @Autowired PlatformTransactionManager transactions;
  @Autowired TestRestTemplate rest;
  @MockBean LlmProvider provider;
  @LocalServerPort int port;
  private User owner, editor, outsider;
  private Project project;
  private UUID diagramId;
  private final List<WebSocket> sockets = new ArrayList<>();
  private final AtomicBoolean infer = new AtomicBoolean();

  @BeforeEach void setup() {
    // Never allow fixture cleanup against a developer's database.
    assertEquals("case_block14_test", jdbc.queryForObject("SELECT current_database()",String.class));
    jdbc.execute("TRUNCATE user_account CASCADE");
    owner = users.save(new User("Ana", "ana@block14.test", "test-hash"));
    editor = users.save(new User("Bruno", "bruno@block14.test", "test-hash"));
    outsider = users.save(new User("Eva", "eva@block14.test", "test-hash"));
    project = projects.save(new Project(owner,"Prueba ventas","Prueba aislada"));
    members.save(new ProjectMember(project,owner,ProjectRole.PROPIETARIO));
    members.save(new ProjectMember(project,editor,ProjectRole.EDITOR));
    diagramId = diagrams.saveAndFlush(new DiagramaClase(project,"Ventas","Modelo de prueba")).getId();
    authenticate(owner);
    infer.set(false);
    when(provider.chat(anyList(),anyInt(),any())).thenAnswer(invocation -> {
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "Groq must run outside a database transaction");
      List<LlmMessage> messages = invocation.getArgument(0);
      String system = messages.getFirst().content();
      String marker = "CONTEXTO UML AUTORIZADO:\n";
      JsonNode context = mapper.readTree(system.substring(system.lastIndexOf(marker)+marker.length()));
      return LlmResponse.success(response(context),20,30,"test-model");
    });
  }
  @AfterEach void cleanup() { sockets.forEach(WebSocket::abort); sockets.clear(); SecurityContextHolder.clearContext(); }

  @Test void changesCoalesceAfterCommitAndPositionsAndStylesDoNotCallGroq() {
    createModel(diagramId);
    assertEquals(1,count("ai_analysis_job"));
    ready(); worker.runOnce();
    verify(provider,times(1)).chat(anyList(),anyInt(),any()); assertEquals(1,count("ai_recommendation"));
    UUID elementId = jdbc.queryForObject("SELECT id FROM elemento_uml WHERE diagrama_id = ? ORDER BY nombre LIMIT 1",UUID.class,diagramId);
    uml.syncPositions(project.getId(),diagramId,owner.getEmail(),new UmlRequests.SyncPositions(List.of(new UmlRequests.PositionUpdate(elementId,999.0,777.0)),revision()));
    ready(); worker.runOnce(); verify(provider,times(1)).chat(anyList(),anyInt(),any());
    var element = uml.getDiagram(project.getId(),diagramId,owner.getEmail()).elements().stream().filter(e->e.id().equals(elementId)).findFirst().orElseThrow();
    uml.updateElement(project.getId(),diagramId,owner.getEmail(),elementId,new UmlRequests.SaveElement(elementId,element.tipoElemento(),element.nombre(),999.0,777.0,"{\"color\":\"purple\"}",revision()));
    ready(); worker.runOnce(); verify(provider,times(1)).chat(anyList(),anyInt(),any());
    assertEquals("SEMANTICALLY_UNCHANGED",jdbc.queryForObject("SELECT last_status FROM ai_analysis_job",String.class));
  }

  @Test void rollbackAndRevisionConflictDoNotProduceVisibleJobsAndNoWorkerRunsBeforeCommit() throws Exception {
    var tx = new TransactionTemplate(transactions);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      tx.executeWithoutResult(status -> {
        addClass(diagramId,"Cliente");
        try { assertTrue(executor.submit(() -> store.claim()).get(5,TimeUnit.SECONDS).isEmpty()); }
        catch(Exception e) { throw new RuntimeException(e); }
        status.setRollbackOnly();
      });
      assertEquals(0,count("ai_analysis_job")); assertEquals(0,count("elemento_uml"));
      addClass(diagramId,"Cliente");
      long sequence=jdbc.queryForObject("SELECT sequence FROM ai_analysis_job",Long.class);
      assertThrows(ConflictException.class, () -> uml.createElement(project.getId(),diagramId,owner.getEmail(),
          new UmlRequests.SaveElement(null,TipoElementoUML.CLASE,"Error",0.0,0.0,"{}",1L)));
      assertEquals(sequence,jdbc.queryForObject("SELECT sequence FROM ai_analysis_job",Long.class));
      verifyNoInteractions(provider);
    } finally { executor.shutdownNow(); }
  }

  @Test void importedModelIsAnalyzedOnceAndProducesOnePendingHypothesisWithoutReplacingConfirmedMemory() {
    infer.set(true);
    String source="{\"elements\":[{\"nombre\":\"Paciente\"},{\"nombre\":\"Medico\"},{\"nombre\":\"Consulta\"}],\"relations\":[]}";
    imports.importJson(project.getId(),diagramId,owner.getEmail(),source,revision());
    assertEquals(1L,jdbc.queryForObject("SELECT sequence FROM ai_analysis_job",Long.class));
    ready(); worker.runOnce();
    verify(provider,times(1)).chat(anyList(),anyInt(),any()); assertEquals(1,count("ai_recommendation"));
    var entries=memory.get(project.getId(),diagramId,owner.getEmail()).entries();
    assertEquals(1,entries.size()); assertEquals(AiMemoryConfidence.INFERRED,entries.getFirst().confidenceScope());
    memory.updateProjectMemory(project.getId(),owner.getEmail(),new AiMemoryRequests.UpdateProjectMemory("Hospital","Gestión de pacientes","Salud","Atención clínica",null));
    store.requestReview(project.getId(),diagramId,owner.getEmail()); ready(); worker.runOnce();
    assertEquals("Gestión de pacientes",memory.get(project.getId(),diagramId,owner.getEmail()).projectMemory().systemDescription());
    assertEquals(1,memory.get(project.getId(),diagramId,owner.getEmail()).entries().size());
  }

  @Test void changingTheDiagramDuringGroqDiscardsTheOldRecommendation() {
    createModel(diagramId);
    doAnswer(invocation -> {
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      List<LlmMessage> messages=invocation.getArgument(0);
      String system=messages.getFirst().content(), marker="CONTEXTO UML AUTORIZADO:\n";
      JsonNode context=mapper.readTree(system.substring(system.lastIndexOf(marker)+marker.length()));
      addClass(diagramId,"FacturaNueva");
      return LlmResponse.success(response(context),20,30,"test-model");
    }).when(provider).chat(anyList(),anyInt(),any());
    ready(); worker.runOnce();
    assertEquals(0,count("ai_recommendation"));
    assertEquals("STALE_DISCARDED",jdbc.queryForObject("SELECT last_status FROM ai_analysis_job",String.class));
    assertTrue(jdbc.queryForObject("SELECT sequence > processed_sequence FROM ai_analysis_job",Boolean.class));
  }

  @Test void tokenReservationsAreAtomicAcrossDiagramsAndOnlyOneWorkerCanClaimADiagram() throws Exception {
    createModel(diagramId); ready();
    ExecutorService executor=Executors.newFixedThreadPool(2);
    try {
      Future<Optional<AiAnalysisStore.Job>> first=executor.submit(store::claim), second=executor.submit(store::claim);
      var a=first.get(5,TimeUnit.SECONDS); var b=second.get(5,TimeUnit.SECONDS);
      assertNotEquals(a.isPresent(),b.isPresent());
      var job1=a.orElseGet(b::orElseThrow);
      UUID other=diagrams.saveAndFlush(new DiagramaClase(project,"Otro","Modelo")).getId();
      createModel(other); ready(); var job2=store.claim().orElseThrow();
      store.updatePolicy(project.getId(),owner.getEmail(),new AiAnalysisStore.PolicyUpdate(true,10,3600,10000L,9000L));
      Future<Boolean> reservation1=executor.submit(()->store.reserve(job1,800));
      Future<Boolean> reservation2=executor.submit(()->store.reserve(job2,800));
      assertNotEquals(reservation1.get(5,TimeUnit.SECONDS),reservation2.get(5,TimeUnit.SECONDS));
      assertEquals(800L,jdbc.queryForObject("SELECT sum(charged_tokens) FROM ai_analysis_run",Long.class));
      verifyNoInteractions(provider);
    } finally { executor.shutdownNow(); }
  }

  @Test void rateLimitPausesTheProjectAndServerErrorsHaveBoundedRetries() {
    createModel(diagramId);
    doReturn(LlmResponse.failure("rate limit",429)).when(provider).chat(anyList(),anyInt(),any());
    ready(); worker.runOnce(); ready(); worker.runOnce();
    verify(provider,times(1)).chat(anyList(),anyInt(),any());
    assertTrue(jdbc.queryForObject("SELECT paused_until > now() FROM ai_analysis_policy",Boolean.class));
    jdbc.update("UPDATE ai_analysis_policy SET paused_until=NULL");
    doReturn(LlmResponse.failure("unavailable",503)).when(provider).chat(anyList(),anyInt(),any());
    ready(); worker.runOnce(); ready(); worker.runOnce(); ready(); worker.runOnce();
    verify(provider,times(3)).chat(anyList(),anyInt(),any());
    assertEquals("ERROR",jdbc.queryForObject("SELECT last_status FROM ai_analysis_job",String.class));
    assertEquals(0,count("ai_recommendation"));
  }

  @Test void insufficientEvidenceAndDisabledAutomationDoNotConsumeTokensAndManualReviewWorks() {
    addClass(diagramId,"Cliente"); ready(); worker.runOnce(); verifyNoInteractions(provider);
    createModel(diagramId);
    store.updatePolicy(project.getId(),owner.getEmail(),new AiAnalysisStore.PolicyUpdate(false,null,null,null,null));
    ready(); worker.runOnce(); verifyNoInteractions(provider);
    store.requestReview(project.getId(),diagramId,owner.getEmail()); worker.runOnce();
    verify(provider,times(1)).chat(anyList(),anyInt(),any());
    assertEquals(1,count("ai_recommendation"));
  }

  @Test void handledOrDismissedRecommendationsAreNotRepeatedForEquivalentEvidence() {
    createModel(diagramId); ready(); worker.runOnce();
    UUID rec=jdbc.queryForObject("SELECT id FROM ai_recommendation",UUID.class);
    store.disposition(project.getId(),diagramId,rec,editor.getEmail(),"DISMISSED");
    store.requestReview(project.getId(),diagramId,owner.getEmail()); worker.runOnce();
    assertEquals(1,count("ai_recommendation"));
    assertEquals(1,count("ai_conversation_message"));
    assertEquals("DUPLICATE_SUPPRESSED",jdbc.queryForObject("SELECT last_status FROM ai_analysis_job",String.class));
  }

  @Test void recommendationsReachTwoRealAuthorizedWebSocketsAndNeverContainOtherUsersPrivateConversation() throws Exception {
    Inbox ana=new Inbox(), bruno=new Inbox(); connect(owner,project.getId(),ana); connect(editor,project.getId(),bruno);
    var privateChat=conversations.create(project.getId(),editor.getEmail(),new AiConversationRequests.Create(AiConversationScope.PRIVATE,diagramId,"Privado"));
    assertEquals("New Chat",privateChat.title());
    conversations.addQuestion(project.getId(),privateChat.id(),editor.getEmail(),new AiConversationRequests.Send(UUID.randomUUID(),"PRIVATE_MARKER_DO_NOT_SEND"));
    assertNull(ana.messages.poll(150,TimeUnit.MILLISECONDS)); assertNull(bruno.messages.poll(150,TimeUnit.MILLISECONDS));
    assertThrows(NotFoundException.class,()->conversations.get(project.getId(),privateChat.id(),owner.getEmail()));
    assertThrows(ForbiddenException.class,()->store.status(project.getId(),diagramId,outsider.getEmail()));
    assertThrows(CompletionException.class,()->connect(outsider,project.getId(),new Inbox()));
    createModel(diagramId); ready(); worker.runOnce();
    String ownerMessage=ana.messages.poll(5,TimeUnit.SECONDS), editorMessage=bruno.messages.poll(5,TimeUnit.SECONDS);
    assertNotNull(ownerMessage); assertNotNull(editorMessage);
    assertEquals("AI_CONVERSATION_MESSAGE",mapper.readTree(ownerMessage).path("type").asText());
    assertTrue(mapper.readTree(editorMessage).path("message").path("content").asText().startsWith("Equipo"));
    var capture=org.mockito.ArgumentCaptor.forClass(List.class);
    verify(provider).chat(capture.capture(),anyInt(),any());
    assertFalse(capture.getValue().toString().contains("PRIVATE_MARKER_DO_NOT_SEND"));
    assertEquals(1,count("ai_recommendation"));
  }

  @Test void jwtRequestsCannotImpersonateTheAgentAndAnUnauthenticatedRequestIsRejected() {
    var group=conversations.create(project.getId(),owner.getEmail(),new AiConversationRequests.Create(AiConversationScope.GROUP,diagramId,"Equipo"));
    doReturn(LlmResponse.success("Bruno, revisemos el UML.",10,10,"mock")).when(provider).chat(anyList(),anyInt(),any());
    HttpHeaders headers=new HttpHeaders(); headers.setBearerAuth(jwt.createToken(editor.getEmail())); headers.setContentType(MediaType.APPLICATION_JSON);
    String body="{\"clientMessageId\":\""+UUID.randomUUID()+"\",\"content\":\"Consulta\",\"senderType\":\"AGENT\",\"senderUserId\":\""+owner.getId()+"\"}";
    var response=rest.exchange("/api/projects/"+project.getId()+"/ai/conversations/"+group.id()+"/messages",HttpMethod.POST,new HttpEntity<>(body,headers),JsonNode.class);
    assertEquals(200,response.getStatusCode().value());
    assertEquals("USER",response.getBody().path("question").path("senderType").asText());
    assertEquals(editor.getId().toString(),response.getBody().path("question").path("senderUserId").asText());
    assertEquals(403,rest.getForEntity("/api/projects/"+project.getId()+"/ai/analysis/diagrams/"+diagramId,String.class).getStatusCode().value());
  }

  @Test void aReplacementWorkerRecoversPersistedJobsAndKeepsAbandonedReservationsCharged() {
    createModel(diagramId); ready(); var abandoned=store.claim().orElseThrow();
    assertTrue(store.reserve(abandoned,1000));
    jdbc.update("UPDATE ai_analysis_job SET lease_until=now()-interval '1 second'");
    new AiAnalysisWorker(store,orchestration,resultValidator,analysisConfig).runOnce();
    assertEquals(1,count("ai_recommendation"));
    assertEquals("ABANDONED",jdbc.queryForObject("SELECT outcome FROM ai_analysis_run WHERE id = ?",String.class,abandoned.token()));
    assertEquals(1000L,jdbc.queryForObject("SELECT charged_tokens FROM ai_analysis_run WHERE id = ?",Long.class,abandoned.token()));
  }

  @Test void noRecommendationAndMissingUsageAreRecordedWithoutPublishingNoise() {
    createModel(diagramId);
    doReturn(LlmResponse.success("{\"hasRecommendation\":false,\"analyzedRevision\":"+revision()+",\"hypothesis\":null}",0,0,"mock"))
        .when(provider).chat(anyList(),anyInt(),any());
    ready(); worker.runOnce();
    assertEquals(0,count("ai_recommendation")); assertEquals(0,count("ai_conversation_message"));
    assertTrue(jdbc.queryForObject("SELECT usage_estimated AND charged_tokens = reserved_tokens FROM ai_analysis_run",Boolean.class));
    assertEquals("NO_RECOMMENDATION",jdbc.queryForObject("SELECT last_status FROM ai_analysis_job",String.class));
  }

  @Test void confirmedHypothesisBecomesUsablePurposeAndIsNotInferredAgain() {
    createModel(diagramId); infer.set(true); ready(); worker.runOnce();
    var entry=memory.get(project.getId(),diagramId,owner.getEmail()).entries().getFirst();
    memory.updateAsConfirmed(project.getId(),entry.id(),editor.getEmail(),new AiMemoryRequests.SaveEntry(AiMemoryEntryType.SYSTEM_CONTEXT,diagramId,"El sistema gestiona la atención de pacientes."));
    assertTrue(snapshots.read(diagramId).confirmedPurpose());
    assertEquals(false,store.status(project.getId(),diagramId,owner.getEmail()).get("needsContext"));
    ready(); worker.runOnce();
    assertEquals(1,memory.get(project.getId(),diagramId,owner.getEmail()).entries().size());
    assertEquals(AiMemoryConfidence.CONFIRMED,memory.get(project.getId(),diagramId,owner.getEmail()).entries().getFirst().confidenceScope());
  }

  @Test void confirmedImageModelAndRestorationAreCoalescedAndEquivalentRestorationDoesNotCallGroqAgain() {
    var imageClasses=List.of("Cliente","Pedido","Producto").stream().map(name ->
        new AiRequests.ImageClass(name,TipoElementoUML.CLASE,0.8,0.0,0.0,List.<AiRequests.ImageAttribute>of(),List.<AiRequests.ImageMethod>of())).toList();
    assistant.applyImageModel(project.getId(),diagramId,owner.getEmail(),new AiRequests.ApplyImageModelRequest(
        new AiRequests.ImageModel(imageClasses,List.of(),List.of("Interpretación visual por confirmar")),AiRequests.ImageImportMode.REPLACE,"prueba.png"),revision());
    assertEquals(1,count("ai_analysis_job")); ready(); worker.runOnce();
    verify(provider,times(1)).chat(anyList(),anyInt(),any());
    var version=versions.createVersion(project.getId(),diagramId,owner.getEmail(),new bo.edu.caseapp.versioning.VersionRequests.CreateVersion("Estado importado"));
    String before=snapshots.read(diagramId).hash();
    versions.restoreVersion(project.getId(),diagramId,version.id(),owner.getEmail(),revision());
    assertEquals(before,snapshots.read(diagramId).hash());
    ready(); worker.runOnce(); verify(provider,times(1)).chat(anyList(),anyInt(),any());
  }

  @Test void projectCallLimitAndReaderPermissionsAreEnforcedBeforeGroq() {
    createModel(diagramId); store.updatePolicy(project.getId(),owner.getEmail(),new AiAnalysisStore.PolicyUpdate(true,1,3600,null,null));
    ready(); worker.runOnce();
    store.requestReview(project.getId(),diagramId,owner.getEmail()); worker.runOnce();
    verify(provider,times(1)).chat(anyList(),anyInt(),any());
    assertEquals("BUDGET_PAUSED",jdbc.queryForObject("SELECT last_status FROM ai_analysis_job",String.class));
    var membership=members.findByProjectIdAndUserId(project.getId(),editor.getId()).orElseThrow(); membership.setRole(ProjectRole.LECTOR); members.save(membership);
    assertThrows(ForbiddenException.class,()->store.requestReview(project.getId(),diagramId,editor.getEmail()));
    assertThrows(ForbiddenException.class,()->store.updatePolicy(project.getId(),editor.getEmail(),new AiAnalysisStore.PolicyUpdate(false,null,null,null,null)));
  }

  @Test void visualChangesDuringProviderFailuresCannotResetTheRetryLimit() {
    createModel(diagramId);
    UUID elementId=jdbc.queryForObject("SELECT id FROM elemento_uml WHERE diagrama_id = ? ORDER BY nombre LIMIT 1",UUID.class,diagramId);
    doAnswer(invocation -> {
      uml.syncPositions(project.getId(),diagramId,owner.getEmail(),new UmlRequests.SyncPositions(
          List.of(new UmlRequests.PositionUpdate(elementId,123.0,456.0)),revision()));
      return LlmResponse.failure("unavailable",503);
    }).when(provider).chat(anyList(),anyInt(),any());
    for (int attempt=0; attempt<5; attempt++) { ready(); worker.runOnce(); }
    verify(provider,times(3)).chat(anyList(),anyInt(),any());
    assertEquals("ERROR",jdbc.queryForObject("SELECT last_status FROM ai_analysis_job",String.class));
    assertFalse(jdbc.queryForObject("SELECT sequence > processed_sequence FROM ai_analysis_job",Boolean.class));
  }

  private void authenticate(User user) { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user.getEmail(),"unused",List.of())); }
  private long revision() { return uml.getDiagramRevision(diagramId); }
  private void addClass(UUID id,String name) { uml.createElement(project.getId(),id,owner.getEmail(),new UmlRequests.SaveElement(null,TipoElementoUML.CLASE,name,10.0,10.0,"{}",uml.getDiagramRevision(id))); }
  private void createModel(UUID id) { addClass(id,"Cliente"); addClass(id,"Pedido"); addClass(id,"Producto"); }
  private void ready() { jdbc.update("UPDATE ai_analysis_job SET due_at=now()-interval '1 second'"); }
  private int count(String table) { if (!Set.of("ai_analysis_job","ai_recommendation","ai_conversation_message","elemento_uml").contains(table)) throw new IllegalArgumentException(); return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class); }
  private String response(JsonNode context) throws Exception {
    Map<String,Object> result=new LinkedHashMap<>(); result.put("hasRecommendation",true); result.put("type","IDENTIFIER");
    result.put("severity","MEDIUM"); result.put("title","Revisar la identificación de instancias");
    result.put("explanation","Equipo, conviene confirmar cómo se distingue cada instancia.");
    result.put("elementIds",List.of(context.path("classes").get(0).path("id").asText()));
    result.put("recommendedChange","Acordar un identificador si el dominio lo requiere.");
    result.put("justification","La identidad debe ser coherente con las reglas confirmadas."); result.put("confidence",0.8);
    result.put("analyzedRevision",context.path("revision").asLong());
    result.put("hypothesis",infer.get()?Map.of("description","Posible sistema de atención clínica", "confidence",0.8):null);
    return mapper.writeValueAsString(result);
  }
  private WebSocket connect(User user,UUID projectId,Inbox inbox) {
    WebSocket socket=HttpClient.newHttpClient().newWebSocketBuilder().header("Origin","http://localhost:5173")
        .subprotocols("case-events-v1","bearer."+jwt.createToken(user.getEmail()))
        .buildAsync(URI.create("ws://localhost:"+port+"/ws/projects/"+projectId+"/chat"),inbox).join();
    sockets.add(socket); return socket;
  }
  private static class Inbox implements WebSocket.Listener {
    final BlockingQueue<String> messages=new LinkedBlockingQueue<>(); final StringBuilder partial=new StringBuilder();
    @Override public void onOpen(WebSocket socket) { socket.request(1); }
    @Override public CompletionStage<?> onText(WebSocket socket,CharSequence text,boolean last) {
      partial.append(text); if (last) { messages.add(partial.toString()); partial.setLength(0); } socket.request(1); return null;
    }
  }
}
