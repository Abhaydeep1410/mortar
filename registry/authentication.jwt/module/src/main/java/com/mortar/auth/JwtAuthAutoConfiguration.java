package com.mortar.auth;

import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Wires the module. The application supplies a {@code UserDetailsService}; everything else (token
 * service, login endpoint, password encoder, the one {@code SecurityFilterChain}) comes from here.
 *
 * <p>The chain is registered with the highest precedence so it wins over Spring Boot's default
 * chain. Applications must not define their own {@code SecurityFilterChain}; they add rules by
 * declaring {@link SecurityConfigurer} beans.
 */
@AutoConfiguration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtAuthAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  Clock mortarClock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnMissingBean
  JwtService mortarJwtService(JwtProperties properties, Clock clock) {
    return new NimbusJwtService(properties, clock);
  }

  @Bean
  @ConditionalOnMissingBean
  PasswordEncoder mortarPasswordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  @Bean
  @ConditionalOnMissingBean
  AuthenticationManager mortarAuthenticationManager(AuthenticationConfiguration configuration)
      throws Exception {
    return configuration.getAuthenticationManager();
  }

  @Bean
  LoginController mortarLoginController(
      AuthenticationManager authenticationManager, JwtService jwtService) {
    return new LoginController(authenticationManager, jwtService);
  }

  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  SecurityFilterChain mortarSecurityFilterChain(
      HttpSecurity http,
      JwtService jwtService,
      JwtProperties properties,
      ObjectProvider<SecurityConfigurer> configurers)
      throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .cors(cors -> cors.configurationSource(corsSource(properties)))
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(
            handling -> handling.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
        .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);

    for (SecurityConfigurer configurer : configurers.orderedStream().toList()) {
      configurer.configure(http);
    }

    http.authorizeHttpRequests(
        requests ->
            requests
                .requestMatchers("/api/auth/login", "/actuator/health", "/actuator/health/**")
                .permitAll()
                .anyRequest()
                .authenticated());
    return http.build();
  }

  /**
   * Built inline, deliberately not a bean. Spring MVC registers a {@code HandlerMappingIntrospector},
   * which is itself a {@code CorsConfigurationSource}; a bean of that type from this module would be
   * skipped by {@code @ConditionalOnMissingBean} or compete with it, and CORS would silently be off.
   */
  private static UrlBasedCorsConfigurationSource corsSource(JwtProperties properties) {
    CorsConfiguration cors = new CorsConfiguration();
    cors.setAllowedOrigins(properties.allowedOrigins());
    cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    cors.setMaxAge(3600L);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", cors);
    return source;
  }
}
