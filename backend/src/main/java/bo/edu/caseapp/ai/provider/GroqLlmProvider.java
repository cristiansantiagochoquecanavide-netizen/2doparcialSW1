package bo.edu.caseapp.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;

@Component
public class GroqLlmProvider implements LlmProvider {
  private static final Logger log = LoggerFactory.getLogger(GroqLlmProvider.class);
  private static final String DEFAULT_MODEL = "qwen/qwen3.8-27b";
  private static final String DEFAULT_BASE_URL = "https://api.groq.com/openai/v1";
  private static final int DEFAULT_TIMEOUT_SECONDS = 30;

  private final String apiKey;
  private final String model;
  private final String baseUrl;
  private final int timeoutSeconds;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  @org.springframework.beans.factory.annotation.Autowired
  public GroqLlmProvider(
      @Value("${app.groq.api-key:}") String apiKey,
      @Value("${app.groq.model:" + DEFAULT_MODEL + "}") String model,
      @Value("${app.groq.base-url:" + DEFAULT_BASE_URL + "}") String baseUrl,
      @Value("${app.groq.timeout-seconds:" + DEFAULT_TIMEOUT_SECONDS + "}") int timeoutSeconds
  ) {
    this(apiKey, model, baseUrl, timeoutSeconds, null, new ObjectMapper());
  }

  public GroqLlmProvider() {
    this("", DEFAULT_MODEL, DEFAULT_BASE_URL, DEFAULT_TIMEOUT_SECONDS, null, new ObjectMapper());
  }

  public GroqLlmProvider(
      String apiKey,
      String model,
      String baseUrl,
      int timeoutSeconds,
      HttpClient httpClient,
      ObjectMapper objectMapper
  ) {
    this.apiKey = apiKey != null ? apiKey.trim() : "";
    this.model = model != null && !model.isBlank() ? model.trim() : DEFAULT_MODEL;
    this.baseUrl = baseUrl != null && !baseUrl.isBlank() ? baseUrl.trim() : DEFAULT_BASE_URL;
    this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
    this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    this.httpClient = httpClient != null
        ? httpClient
        : HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(Math.min(10, this.timeoutSeconds)))
            .build();
  }

  @Override
  public String getProviderName() {
    return "GROQ";
  }

  @Override
  public String getModelName() {
    return getEffectiveModel();
  }

  @Override
  public boolean isConfigured() {
    return !getEffectiveApiKey().isBlank();
  }

  public String getEffectiveApiKey() {
    if (apiKey != null && !apiKey.isBlank()) {
      return apiKey.trim();
    }
    String envKey = System.getenv("GROQ_API_KEY");
    if (envKey != null && !envKey.isBlank()) {
      return envKey.trim();
    }
    return "";
  }

  public String getEffectiveModel() {
    if (model != null && !model.isBlank()) {
      return model.trim();
    }
    String envModel = System.getenv("GROQ_MODEL");
    if (envModel != null && !envModel.isBlank()) {
      return envModel.trim();
    }
    return DEFAULT_MODEL;
  }

  @Override
  public LlmResponse chat(List<LlmMessage> messages, Integer maxTokens, Double temperature) {
    String key = getEffectiveApiKey();
    if (key.isBlank()) {
      log.warn("Llamada a Groq omitida: GROQ_API_KEY no está configurada.");
      return LlmResponse.failure("GROQ_API_KEY no está configurada en el entorno.", 401);
    }

    try {
      ObjectNode payload = objectMapper.createObjectNode();
      payload.put("model", getEffectiveModel());
      payload.put("max_tokens", maxTokens != null ? maxTokens : 800);
      payload.put("temperature", temperature != null ? temperature : 0.2);

      ArrayNode messagesArray = payload.putArray("messages");
      for (LlmMessage msg : messages) {
        ObjectNode msgNode = messagesArray.addObject();
        msgNode.put("role", msg.role());
        msgNode.put("content", msg.content());
      }

      return executePost(payload.toString(), key);
    } catch (Exception e) {
      log.error("Excepción inesperada al invocar chat en Groq ({})", e.getClass().getSimpleName());
      return LlmResponse.failure("Error interno al invocar Groq.", 500);
    }
  }

  @Override
  public LlmResponse analyzeImage(String prompt, String imageBase64, String mimeType, Integer maxTokens) {
    String key = getEffectiveApiKey();
    if (key.isBlank()) {
      log.warn("Análisis de imagen en Groq omitido: GROQ_API_KEY no configurada.");
      return LlmResponse.failure("GROQ_API_KEY no está configurada en el entorno.", 401);
    }

    try {
      String cleanBase64 = cleanBase64(imageBase64);
      String effectiveMime = (mimeType != null && !mimeType.isBlank()) ? mimeType.trim() : "image/png";

      ObjectNode payload = objectMapper.createObjectNode();
      payload.put("model", getEffectiveModel());
      payload.put("max_tokens", maxTokens != null ? maxTokens : 1600);
      payload.put("temperature", 0.1);

      ArrayNode messagesArray = payload.putArray("messages");
      ObjectNode userMsgNode = messagesArray.addObject();
      userMsgNode.put("role", "user");

      ArrayNode contentArray = userMsgNode.putArray("content");
      
      // Bloque de texto
      ObjectNode textPart = contentArray.addObject();
      textPart.put("type", "text");
      textPart.put("text", prompt);

      // Bloque de imagen multimodal compatible con OpenAI
      ObjectNode imagePart = contentArray.addObject();
      imagePart.put("type", "image_url");
      ObjectNode imageUrlNode = imagePart.putObject("image_url");
      imageUrlNode.put("url", "data:" + effectiveMime + ";base64," + cleanBase64);

      return executePost(payload.toString(), key);
    } catch (Exception e) {
      log.error("Excepción inesperada al analizar imagen en Groq ({})", e.getClass().getSimpleName());
      return LlmResponse.failure("Error interno al analizar imagen en Groq.", 500);
    }
  }

  private LlmResponse executePost(String jsonPayload, String key) {
    long startTime = System.currentTimeMillis();
    String endpoint = baseUrl.replaceAll("/+$", "") + "/chat/completions";

    try {
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(endpoint))
          .header("Authorization", "Bearer " + key)
          .header("Content-Type", "application/json")
          .timeout(Duration.ofSeconds(timeoutSeconds))
          .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
          .build();

      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      long latency = System.currentTimeMillis() - startTime;
      int statusCode = response.statusCode();

      if (statusCode == 200) {
        JsonNode root = objectMapper.readTree(response.body());
        String text = "";
        JsonNode choices = root.get("choices");
        if (choices != null && choices.isArray() && choices.size() > 0) {
          JsonNode msg = choices.get(0).get("message");
          if (msg != null && msg.has("content")) {
            text = msg.get("content").asText();
          }
        }

        if (text == null || text.isBlank()) {
          log.warn("GroqCloud devolvió una respuesta vacía o sin choices (HTTP 200) tras {}ms", latency);
          return LlmResponse.failure("GroqCloud devolvió una respuesta vacía o malformada.", 502);
        }

        int promptTokens = 0;
        int completionTokens = 0;
        JsonNode usage = root.get("usage");
        if (usage != null) {
          promptTokens = usage.path("prompt_tokens").asInt(0);
          completionTokens = usage.path("completion_tokens").asInt(0);
        }

        String returnedModel = root.path("model").asText(getEffectiveModel());
        log.info("GroqCloud respuesta exitosa [{}] tokens prompt={}, completion={}, latencia={}ms",
            returnedModel, promptTokens, completionTokens, latency);

        return LlmResponse.success(text, promptTokens, completionTokens, returnedModel);
      } else if (statusCode == 429) {
        String retryAfter = response.headers().firstValue("Retry-After").orElse("segundos");
        log.warn("GroqCloud límite de tasa (HTTP 429). Retry-After: {}", retryAfter);
        return LlmResponse.failure("Límite de tasa excedido en GroqCloud (HTTP 429). Por favor espere antes de reintentar.", 429);
      } else if (statusCode == 401 || statusCode == 403) {
        log.error("GroqCloud error de autenticación (HTTP {}). Verifique GROQ_API_KEY.", statusCode);
        return LlmResponse.failure("Credencial inválida o no autorizada para GroqCloud (HTTP " + statusCode + ").", statusCode);
      } else {
        log.warn("GroqCloud respondió HTTP {}", statusCode);
        return LlmResponse.failure("GroqCloud respondió error HTTP " + statusCode + ".", statusCode);
      }
    } catch (HttpTimeoutException e) {
      long latency = System.currentTimeMillis() - startTime;
      log.warn("Timeout al conectar con GroqCloud tras {}ms", latency);
      return LlmResponse.failure("Tiempo de espera agotado al conectar con GroqCloud (" + timeoutSeconds + "s).", 504);
    } catch (IOException e) {
      log.warn("Error de E/S al conectar con GroqCloud ({}) tras {}ms", e.getClass().getSimpleName(), System.currentTimeMillis() - startTime);
      return LlmResponse.failure("Error de comunicación de red con GroqCloud.", 503);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return LlmResponse.failure("Operación interrumpida con GroqCloud.", 500);
    }
  }

  private String cleanBase64(String input) {
    if (input == null) return "";
    int commaIdx = input.indexOf(',');
    if (input.startsWith("data:") && commaIdx >= 0) {
      return input.substring(commaIdx + 1).trim();
    }
    return input.trim();
  }

}
