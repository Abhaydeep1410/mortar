package com.mortar.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * Exercises the module end to end in a plain Spring web context: auto-configuration, login,
 * the filter chain, 401 versus 403, method security and CORS. The application contributes only a
 * {@code UserDetailsService}, exactly as a generated app would.
 */
@SpringJUnitWebConfig(JwtSecurityIntegrationTest.TestApp.class)
@TestPropertySource(
    properties = {
      "mortar.auth.jwt.secret=integration-secret-integration-secret-012345",
      "mortar.auth.jwt.issuer=integration",
      "mortar.auth.jwt.allowed-origins=http://localhost:3000"
    })
class JwtSecurityIntegrationTest {

  @Configuration
  @EnableWebMvc
  @EnableWebSecurity
  @EnableMethodSecurity
  @Import(JwtAuthAutoConfiguration.class)
  static class TestApp {

    @Bean
    UserDetailsService users() {
      var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
      return new InMemoryUserDetailsManager(
          User.withUsername("admin").password(encoder.encode("Admin#12345")).roles("ADMIN").build(),
          User.withUsername("viewer").password(encoder.encode("Viewer#12345")).roles("VIEWER").build());
    }

    @Bean
    PingController pingController() {
      return new PingController();
    }
  }

  @RestController
  static class PingController {

    @GetMapping("/api/ping")
    public String ping() {
      return "pong";
    }

    @GetMapping("/api/admin-only")
    @PreAuthorize("hasRole('ADMIN')")
    public String adminOnly() {
      return "secret";
    }
  }

  @Autowired private WebApplicationContext context;
  @Autowired private JwtService jwtService;

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private String login(String username, String password) throws Exception {
    String body = mvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").isNotEmpty())
        .andReturn()
        .getResponse()
        .getContentAsString();
    return JsonPath.read(body, "$.token");
  }

  @Test
  void loginReturnsTokenCarryingTheUsersRoles() throws Exception {
    Claims claims = jwtService.validate(login("admin", "Admin#12345"));

    assertThat(claims.subject()).isEqualTo("admin");
    assertThat(claims.roles()).containsExactly("ADMIN");
  }

  @Test
  void wrongPasswordAndUnknownUserAreUnauthorized() throws Exception {
    for (String[] credentials : new String[][] {{"admin", "nope"}, {"ghost", "Admin#12345"}}) {
      mvc.perform(post("/api/auth/login")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"username\":\"" + credentials[0] + "\",\"password\":\"" + credentials[1] + "\"}"))
          .andExpect(status().isUnauthorized());
    }
  }

  @Test
  void incompleteOrMalformedLoginBodiesAreBadRequests() throws Exception {
    for (String body : new String[] {"{}", "{\"username\":\"admin\"}", "{\"username\":\"admin\",\"password\":\" \"}", "not json"}) {
      mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isBadRequest());
    }
  }

  @Test
  void protectedEndpointsRequireAValidToken() throws Exception {
    mvc.perform(get("/api/ping")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/ping").header("Authorization", "Bearer garbage")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/ping").header("Authorization", "Bearer " + login("viewer", "Viewer#12345")))
        .andExpect(status().isOk());
  }

  @Test
  void authenticatedButNotPermittedIsForbiddenNotUnauthorized() throws Exception {
    mvc.perform(get("/api/admin-only").header("Authorization", "Bearer " + login("viewer", "Viewer#12345")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/admin-only").header("Authorization", "Bearer " + login("admin", "Admin#12345")))
        .andExpect(status().isOk());
  }

  @Test
  void corsPreflightFromTheFrontendOriginIsAllowed() throws Exception {
    mvc.perform(options("/api/ping")
            .header("Origin", "http://localhost:3000")
            .header("Access-Control-Request-Method", "GET")
            .header("Access-Control-Request-Headers", "Authorization"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
  }

  @Test
  void corsPreflightFromAnotherOriginIsRejected() throws Exception {
    mvc.perform(options("/api/ping")
            .header("Origin", "http://evil.example")
            .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isForbidden());
  }
}
