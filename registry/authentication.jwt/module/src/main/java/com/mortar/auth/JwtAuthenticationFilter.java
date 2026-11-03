package com.mortar.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates requests carrying {@code Authorization: Bearer <token>}. A missing or invalid token
 * leaves the request unauthenticated; the entry point of the filter chain turns that into 401.
 * Deliberately not a Spring bean, so the servlet container does not register it a second time.
 */
public final class JwtAuthenticationFilter extends OncePerRequestFilter {

  private static final String BEARER_PREFIX = "Bearer ";

  private final JwtService jwtService;

  public JwtAuthenticationFilter(JwtService jwtService) {
    this.jwtService = jwtService;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    SecurityContextHolderStrategy strategy = SecurityContextHolder.getContextHolderStrategy();
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header != null && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
      String token = header.substring(BEARER_PREFIX.length()).trim();
      try {
        Claims claims = jwtService.validate(token);
        List<GrantedAuthority> authorities =
            claims.roles().stream()
                .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        SecurityContext context = strategy.createEmptyContext();
        context.setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(claims.subject(), null, authorities));
        strategy.setContext(context);
      } catch (InvalidTokenException e) {
        strategy.clearContext();
      }
    }
    chain.doFilter(request, response);
  }
}
