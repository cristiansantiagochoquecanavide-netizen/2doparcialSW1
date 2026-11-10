package bo.edu.caseapp.ai;

import bo.edu.caseapp.ai.provider.GroqLlmProvider;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

@Service
public class GroqTranscriptionService {
  private final GroqLlmProvider provider;
  private final String baseUrl;
  private final String model;
  private final int timeoutSeconds;

  public GroqTranscriptionService(
      GroqLlmProvider provider,
      @Value("${app.groq.base-url:https://api.groq.com/openai/v1}") String baseUrl,
      @Value("${app.groq.transcription-model:whisper-large-v3-turbo}") String model,
      @Value("${app.groq.timeout-seconds:30}") int timeoutSeconds
  ) {
    this.provider = provider;
    this.baseUrl = baseUrl.replaceAll("/+$", "");
    this.model = model;
    this.timeoutSeconds = timeoutSeconds;
  }

  public String transcribe(MultipartFile audio) {
    if (audio == null || audio.isEmpty()) throw new ResponseStatusException(BAD_REQUEST, "No se recibió audio.");
    if (audio.getSize() > 25L * 1024 * 1024) throw new ResponseStatusException(BAD_REQUEST, "El audio supera el límite de 25 MB.");
    String apiKey = provider.getEffectiveApiKey();
    if (apiKey.isBlank()) throw new ResponseStatusException(SERVICE_UNAVAILABLE, "La transcripción requiere configurar GROQ_API_KEY en el servidor.");

    try {
      String filename = audio.getOriginalFilename() != null && audio.getOriginalFilename().toLowerCase().endsWith(".mp4") ? "voice.mp4" : "voice.webm";
      MediaType mediaType = filename.endsWith(".mp4") ? MediaType.parseMediaType("audio/mp4") : MediaType.parseMediaType("audio/webm");
      ByteArrayResource file = new ByteArrayResource(audio.getBytes()) {
        @Override public String getFilename() { return filename; }
      };
      HttpHeaders fileHeaders = new HttpHeaders();
      fileHeaders.setContentType(mediaType);
      LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
      form.add("file", new HttpEntity<>(file, fileHeaders));
      form.add("model", model);
      form.add("language", "es");
      form.add("response_format", "json");
      form.add("prompt", "Transcribe en español comandos de modelado UML: clases, atributos, métodos, asociaciones, herencia y multiplicidades.");

      HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.min(10, timeoutSeconds))).build();
      JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
      factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
      JsonNode response = RestClient.builder().requestFactory(factory).build()
          .post().uri(baseUrl + "/audio/transcriptions")
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
          .contentType(MediaType.MULTIPART_FORM_DATA)
          .body(form).retrieve().body(JsonNode.class);
      String text = response == null ? "" : response.path("text").asText("").trim();
      if (text.isBlank()) throw new ResponseStatusException(BAD_GATEWAY, "Groq no devolvió texto transcrito.");
      return text;
    } catch (ResponseStatusException error) {
      throw error;
    } catch (Exception error) {
      throw new ResponseStatusException(BAD_GATEWAY, "No se pudo completar la transcripción de voz.");
    }
  }
}
