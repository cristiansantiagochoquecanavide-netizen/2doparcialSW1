package bo.edu.caseapp.config;

import bo.edu.caseapp.auth.JwtAuthenticationFilter;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.*;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {
  @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
  @Bean AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception { return config.getAuthenticationManager(); }
  @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter) throws Exception {
    return http.csrf(csrf -> csrf.disable()).cors(cors -> { }).sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // WebSocket upgrades are permitAll at the servlet filter because browsers cannot attach Authorization headers;
        // JwtWebSocketHandshakeInterceptor validates the bearer token and project membership before accepting them.
        .authorizeHttpRequests(auth -> auth.requestMatchers("/api/auth/**", "/api/health", "/ws/diagrams/**", "/ws/projects/**").permitAll().anyRequest().authenticated())
        .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class).build();
  }
}
