package com.mortar.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class JwtAuthenticationFilterTest {

  private static final String SECRET = "filter-test-secret-filter-test-secret-0123456";

  private NimbusJwtService jwtService;
  private JwtAuthenticationFilter filter;

  @BeforeEach
  void setUp() {
    MutableClock clock = new MutableClock(Instant.parse("2026-11-02T10:00:00Z"));
    jwtService = new NimbusJwtService(new JwtProperties(SECRET, 60, "mortar", List.of()), clock);
    filter = new JwtAuthenticationFilter(jwtService);
    SecurityContextHolder.clearContext();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  private MockFilterChain run(String authorizationHeader) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/anything");
    if (authorizationHeader != null) {
      request.addHeader("Authorization", authorizationHeader);
    }
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(request, new MockHttpServletResponse(), chain);
    assertThat(chain.getRequest()).as("the chain must always continue").isNotNull();
    return chain;
  }

  @Test
  void validTokenAuthenticatesWithRoleAuthorities() throws Exception {
    run("Bearer " + jwtService.issue("alice", List.of("ADMIN", "VIEWER")));

    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    assertThat(authentication).isNotNull();
    assertThat(authentication.isAuthenticated()).isTrue();
    assertThat(authentication.getName()).isEqualTo("alice");
    assertThat(authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority))
        .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_VIEWER");
  }

  @Test
  void schemeIsCaseInsensitive() throws Exception {
    run("bearer " + jwtService.issue("alice", List.of()));

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
  }

  @Test
  void missingHeaderLeavesRequestUnauthenticated() throws Exception {
    run(null);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void otherSchemesAreIgnored() throws Exception {
    run("Basic YWRtaW46c2VjcmV0");

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void invalidTokenLeavesRequestUnauthenticated() throws Exception {
    run("Bearer not.a.token");
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

    run("Bearer ");
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }
}
