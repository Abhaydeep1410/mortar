package com.mortar.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Records successful writes. It sits inside the security chain after authorization, so a request
 * that was rejected (401, 403) never reaches it, and it checks for a 2xx answer before recording.
 * The record is written synchronously after the handler has run. If saving fails the error is
 * logged and the response is left as it was: the client already has its answer.
 */
final class AuditFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(AuditFilter.class);

  private final AuditPathMatcher matcher;
  private final AuditLogRepository repository;
  private final Clock clock;

  AuditFilter(AuditPathMatcher matcher, AuditLogRepository repository, Clock clock) {
    this.matcher = matcher;
    this.repository = repository;
    this.clock = clock;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    Optional<AuditPathMatcher.Target> target = matcher.match(request.getMethod(), path);

    chain.doFilter(request, response);

    if (target.isEmpty() || response.getStatus() < 200 || response.getStatus() >= 300) {
      return;
    }
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
        || !authentication.isAuthenticated()
        || authentication instanceof AnonymousAuthenticationToken) {
      return;
    }
    AuditPathMatcher.Target write = target.get();
    Long entityId =
        write.idFromPath() != null
            ? write.idFromPath()
            : AuditPathMatcher.idFromLocation(response.getHeader("Location"), path).orElse(null);
    try {
      repository.save(
          new AuditLog(authentication.getName(), write.action(), write.entity(), entityId, clock.instant()));
    } catch (RuntimeException e) {
      log.error(
          "AUDIT RECORD LOST: {} {} {} id={} by {}",
          write.action(), write.entity(), path, entityId, authentication.getName(), e);
    }
  }
}
