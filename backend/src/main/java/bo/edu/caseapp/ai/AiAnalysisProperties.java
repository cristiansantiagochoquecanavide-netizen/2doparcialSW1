package bo.edu.caseapp.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Min;

@Component @ConfigurationProperties(prefix = "app.ai.proactive") @Validated
public class AiAnalysisProperties {
  private boolean enabled = true;
  @Min(1) private int debounceSeconds = 12;
  @Min(1) private int maxCalls = 8;
  @Min(60) private int periodSeconds = 3600;
  @Min(1) private int tokenBudget = 100000;
  @Min(0) private int explicitReserve = 50000;
  @Min(1) private int maxOutputTokens = 700;
  @Min(1) private int maxAttempts = 3;
  @Min(1) private int retrySeconds = 60;
  @Min(1) private int rateLimitPauseSeconds = 120;
  @Min(1) private int leaseSeconds = 120;
  @Min(1) private int dedupHours = 24;
  public boolean isEnabled() { return enabled; } public void setEnabled(boolean v) { enabled=v; }
  public int getDebounceSeconds() { return debounceSeconds; } public void setDebounceSeconds(int v) { debounceSeconds=v; }
  public int getMaxCalls() { return maxCalls; } public void setMaxCalls(int v) { maxCalls=v; }
  public int getPeriodSeconds() { return periodSeconds; } public void setPeriodSeconds(int v) { periodSeconds=v; }
  public int getTokenBudget() { return tokenBudget; } public void setTokenBudget(int v) { tokenBudget=v; }
  public int getExplicitReserve() { return explicitReserve; } public void setExplicitReserve(int v) { explicitReserve=v; }
  public int getMaxOutputTokens() { return maxOutputTokens; } public void setMaxOutputTokens(int v) { maxOutputTokens=v; }
  public int getMaxAttempts() { return maxAttempts; } public void setMaxAttempts(int v) { maxAttempts=v; }
  public int getRetrySeconds() { return retrySeconds; } public void setRetrySeconds(int v) { retrySeconds=v; }
  public int getRateLimitPauseSeconds() { return rateLimitPauseSeconds; } public void setRateLimitPauseSeconds(int v) { rateLimitPauseSeconds=v; }
  public int getLeaseSeconds() { return leaseSeconds; } public void setLeaseSeconds(int v) { leaseSeconds=v; }
  public int getDedupHours() { return dedupHours; } public void setDedupHours(int v) { dedupHours=v; }
}
