package bo.edu.caseapp.ai.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GroqLlmProviderTest {
  private HttpClient mockHttpClient;
  private HttpResponse<String> mockHttpResponse;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    mockHttpClient = mock(HttpClient.class);
    mockHttpResponse = mock(HttpResponse.class);
    objectMapper = new ObjectMapper();
  }

  @Test
  void chat_whenApiKeyMissing_returnsFailureWithoutHttpCall() throws Exception {
    GroqLlmProvider provider = new GroqLlmProvider("", "qwen/qwen3.8-27b", "https://api.groq.com/openai/v1", 30, mockHttpClient, objectMapper);

    LlmResponse response = provider.chat(List.of(LlmMessage.user("Hola")), 100, 0.2);

    assertFalse(response.success());
    assertEquals(401, response.httpStatusCode());
    assertTrue(response.errorMessage().contains("GROQ_API_KEY no está configurada"));
    verifyNoInteractions(mockHttpClient);
  }

  @Test
  void chat_whenSuccess_parsesContentAndUsageCorrectly() throws Exception {
    String jsonResponse = """
        {
          "id": "chatcmpl-test",
          "model": "qwen/qwen3.8-27b",
          "choices": [
            {
              "index": 0,
              "message": {
                "role": "assistant",
                "content": "Respuesta desde Groq"
              }
            }
          ],
          "usage": {
            "prompt_tokens": 15,
            "completion_tokens": 5,
            "total_tokens": 20
          }
        }
        """;

    when(mockHttpResponse.statusCode()).thenReturn(200);
    when(mockHttpResponse.body()).thenReturn(jsonResponse);
    when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(mockHttpResponse);

    GroqLlmProvider provider = new GroqLlmProvider("dummy-key", "qwen/qwen3.8-27b", "https://api.groq.com/openai/v1", 30, mockHttpClient, objectMapper);

    LlmResponse response = provider.chat(List.of(LlmMessage.system("Eres un asistente"), LlmMessage.user("Hola")), 100, 0.2);

    assertTrue(response.success());
    assertEquals("Respuesta desde Groq", response.content());
    assertEquals(15, response.promptTokens());
    assertEquals(5, response.completionTokens());
    assertEquals("qwen/qwen3.8-27b", response.model());
    assertEquals(200, response.httpStatusCode());
  }

  @Test
  void chat_whenRateLimit429_handlesGracefully() throws Exception {
    when(mockHttpResponse.statusCode()).thenReturn(429);
    HttpHeaders headers = HttpHeaders.of(Map.of("Retry-After", List.of("5")), (k, v) -> true);
    when(mockHttpResponse.headers()).thenReturn(headers);
    when(mockHttpResponse.body()).thenReturn("{\"error\":{\"message\":\"Rate limit reached\"}}");
    when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(mockHttpResponse);

    GroqLlmProvider provider = new GroqLlmProvider("dummy-key", "qwen/qwen3.8-27b", "https://api.groq.com/openai/v1", 30, mockHttpClient, objectMapper);

    LlmResponse response = provider.chat(List.of(LlmMessage.user("Hola")), 100, 0.2);

    assertFalse(response.success());
    assertEquals(429, response.httpStatusCode());
    assertTrue(response.errorMessage().contains("429"));
  }

  @Test
  void chat_whenAuthError401_returnsFriendlyErrorWithoutLeakingKey() throws Exception {
    when(mockHttpResponse.statusCode()).thenReturn(401);
    when(mockHttpResponse.body()).thenReturn("{\"error\":{\"message\":\"Invalid API Key\"}}");
    when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(mockHttpResponse);

    GroqLlmProvider provider = new GroqLlmProvider("secret-1234", "qwen/qwen3.8-27b", "https://api.groq.com/openai/v1", 30, mockHttpClient, objectMapper);

    LlmResponse response = provider.chat(List.of(LlmMessage.user("Hola")), 100, 0.2);

    assertFalse(response.success());
    assertEquals(401, response.httpStatusCode());
    assertFalse(response.errorMessage().contains("secret-1234"), "La clave jamás debe aparecer en el mensaje de error");
  }

  @Test
  void chat_whenTimeout_returns504() throws Exception {
    when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenThrow(new HttpTimeoutException("request timed out"));

    GroqLlmProvider provider = new GroqLlmProvider("dummy-key", "qwen/qwen3.8-27b", "https://api.groq.com/openai/v1", 10, mockHttpClient, objectMapper);

    LlmResponse response = provider.chat(List.of(LlmMessage.user("Hola")), 100, 0.2);

    assertFalse(response.success());
    assertEquals(504, response.httpStatusCode());
    assertTrue(response.errorMessage().contains("Tiempo de espera agotado"));
  }

  @Test
  void chat_whenServerError_returnsSanitizedFailure() throws Exception {
    when(mockHttpResponse.statusCode()).thenReturn(503);
    when(mockHttpResponse.body()).thenReturn("{\"error\":{\"message\":\"sensitive provider detail\"}}");
    when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(mockHttpResponse);

    GroqLlmProvider provider = new GroqLlmProvider("dummy-key", "qwen/qwen3.8-27b", "https://api.groq.com/openai/v1", 30, mockHttpClient, objectMapper);
    LlmResponse response = provider.chat(List.of(LlmMessage.user("Hola")), 100, 0.2);

    assertFalse(response.success());
    assertEquals(503, response.httpStatusCode());
    assertFalse(response.errorMessage().contains("sensitive provider detail"));
  }

  @Test
  void chat_whenProviderReturnsEmptyOrMalformedChoice_rejectsResponse() throws Exception {
    when(mockHttpResponse.statusCode()).thenReturn(200);
    when(mockHttpResponse.body()).thenReturn("{\"choices\":[]}");
    when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(mockHttpResponse);

    GroqLlmProvider provider = new GroqLlmProvider("dummy-key", "qwen/qwen3.8-27b", "https://api.groq.com/openai/v1", 30, mockHttpClient, objectMapper);
    LlmResponse response = provider.chat(List.of(LlmMessage.user("Hola")), 100, 0.2);

    assertFalse(response.success());
    assertEquals(502, response.httpStatusCode());
  }

  @Test
  void analyzeImage_serializesOpenAiCompatibleMultimodalPayload() throws Exception {
    String jsonResponse = """
        {
          "id": "chatcmpl-test-vision",
          "model": "qwen/qwen3.8-27b",
          "choices": [
            {
              "index": 0,
              "message": {
                "role": "assistant",
                "content": "{\\"classes\\":[{\\"name\\":\\"Usuario\\",\\"attributes\\":[],\\"methods\\":[]}],\\"relations\\":[]}"
              }
            }
          ],
          "usage": {
            "prompt_tokens": 120,
            "completion_tokens": 30,
            "total_tokens": 150
          }
        }
        """;

    ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
    when(mockHttpResponse.statusCode()).thenReturn(200);
    when(mockHttpResponse.body()).thenReturn(jsonResponse);
    when(mockHttpClient.send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class)))
        .thenReturn(mockHttpResponse);

    GroqLlmProvider provider = new GroqLlmProvider("dummy-key", "qwen/qwen3.8-27b", "https://api.groq.com/openai/v1", 30, mockHttpClient, objectMapper);

    LlmResponse response = provider.analyzeImage("Analiza el diagrama", "iVBORw0KGgoAAAANSUhEUg==", "image/png", 500);

    assertTrue(response.success());
    assertNotNull(response.content());
    HttpRequest sent = requestCaptor.getValue();
    assertEquals("POST", sent.method());
    assertTrue(sent.uri().toString().endsWith("/chat/completions"));
    assertEquals("Bearer dummy-key", sent.headers().firstValue("Authorization").orElse(""));
  }

  @Test
  void providerConfiguration_readsModelAndName() {
    GroqLlmProvider provider = new GroqLlmProvider("test-key", "qwen/qwen3.8-27b", "https://api.groq.com/openai/v1", 30, mockHttpClient, objectMapper);
    assertEquals("GROQ", provider.getProviderName());
    assertEquals("qwen/qwen3.8-27b", provider.getModelName());
  }
}
