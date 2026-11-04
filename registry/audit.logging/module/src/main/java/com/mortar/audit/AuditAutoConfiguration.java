package com.mortar.audit;

import com.mortar.auth.JwtAuthAutoConfiguration;
import com.mortar.auth.SecurityConfigurer;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Wires the module through the {@link SecurityConfigurer} hook of {@code authentication.jwt}.
 *
 * <p>{@code @AutoConfigurationPackage} adds this package to the places Spring Boot scans for JPA
 * entities and repositories, so {@link AuditLog} is found without the application naming it and
 * without switching off the application's own repository scanning.
 */
@AutoConfiguration(after = JwtAuthAutoConfiguration.class)
@AutoConfigurationPackage(basePackageClasses = AuditLog.class)
@EnableConfigurationProperties(AuditProperties.class)
public class AuditAutoConfiguration {

  @Bean
  AuditLogController mortarAuditLogController(AuditLogRepository repository) {
    return new AuditLogController(repository);
  }

  /** Not a filter bean: it is added to the security chain only, so the container does not run it twice. */
  @Bean
  SecurityConfigurer mortarAuditSecurityConfigurer(
      AuditProperties properties, AuditLogRepository repository, ObjectProvider<Clock> clock) {
    AuditFilter filter =
        new AuditFilter(new AuditPathMatcher(properties), repository, clock.getIfAvailable(Clock::systemUTC));
    return http -> {
      http.addFilterAfter(filter, AuthorizationFilter.class);
      http.authorizeHttpRequests(
          requests -> {
            requests.requestMatchers(HttpMethod.GET, "/api/audit-logs").hasRole(properties.adminRole());
            requests.requestMatchers("/api/audit-logs", "/api/audit-logs/**").denyAll();
          });
    };
  }
}
