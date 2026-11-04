package com.mortar.rbac;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.mortar.auth.JwtAuthAutoConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * Both modules in a plain Spring web context, exactly as a generated app would use them: the app
 * supplies users and endpoints, {@code authentication.jwt} supplies login and the filter chain,
 * and this module supplies the permission rules.
 */
@SpringJUnitWebConfig(RbacSecurityIntegrationTest.TestApp.class)
@TestPropertySource(
    properties = {
      "mortar.auth.jwt.secret=integration-secret-integration-secret-012345",
      "mortar.auth.jwt.issuer=rbac-integration",
      "mortar.auth.rbac.resources[0].path=contacts",
      "mortar.auth.rbac.resources[0].name=contact",
      "mortar.auth.rbac.resources[1].path=notes",
      "mortar.auth.rbac.resources[1].name=note",
      "mortar.auth.rbac.roles[0].name=ADMIN",
      "mortar.auth.rbac.roles[0].permissions[0]=*",
      "mortar.auth.rbac.roles[1].name=SALES_REP",
      "mortar.auth.rbac.roles[1].permissions[0]=contact:read",
      "mortar.auth.rbac.roles[1].permissions[1]=contact:write",
      "mortar.auth.rbac.roles[1].permissions[2]=note:*",
      "mortar.auth.rbac.roles[2].name=VIEWER",
      "mortar.auth.rbac.roles[2].permissions[0]=contact:read",
      "mortar.auth.rbac.roles[2].permissions[1]=contact:export",
      "mortar.auth.rbac.roles[2].permissions[2]=note:read"
    })
class RbacSecurityIntegrationTest {

  @Configuration
  @EnableWebMvc
  @EnableWebSecurity
  @Import({JwtAuthAutoConfiguration.class, RbacAutoConfiguration.class})
  static class TestApp {

    @Bean
    UserDetailsService users() {
      var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
      return new InMemoryUserDetailsManager(
          User.withUsername("admin").password(encoder.encode("Admin#12345")).roles("ADMIN").build(),
          User.withUsername("rep").password(encoder.encode("Rep#12345")).roles("SALES_REP").build(),
          User.withUsername("viewer").password(encoder.encode("Viewer#12345")).roles("VIEWER").build(),
          User.withUsername("guest").password(encoder.encode("Guest#12345")).roles("GUEST").build());
    }

    @Bean
    Endpoints endpoints() {
      return new Endpoints();
    }
  }

  @RestController
  static class Endpoints {
    @GetMapping("/api/contacts")
    public String listContacts() {
      return "list";
    }

    @GetMapping("/api/contacts/export.csv")
    public String exportContacts() {
      return "csv";
    }

    @GetMapping("/api/contacts/{id}")
    public String oneContact(@PathVariable String id) {
      return "one";
    }

    @PostMapping("/api/contacts")
    public String createContact() {
      return "created";
    }

    @PutMapping("/api/contacts/{id}")
    public String updateContact(@PathVariable String id) {
      return "updated";
    }

    @DeleteMapping("/api/contacts/{id}")
    public String deleteContact(@PathVariable String id) {
      return "deleted";
    }

    @GetMapping("/api/notes")
    public String listNotes() {
      return "notes";
    }

    @PostMapping("/api/notes")
    public String createNote() {
      return "note-created";
    }

    @DeleteMapping("/api/notes/{id}")
    public String deleteNote(@PathVariable String id) {
      return "note-deleted";
    }

    @GetMapping("/api/unmapped")
    public String unmapped() {
      return "open-to-any-signed-in-user";
    }
  }

  @Autowired private WebApplicationContext context;

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private String token(String username, String password) throws Exception {
    String body =
        mvc.perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.token");
  }

  private ResultActions as(String user, String password, MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(request.header("Authorization", "Bearer " + token(user, password)));
  }

  @Test
  void anonymousCallersGet401OnProtectedResourcesAndLoginStaysOpen() throws Exception {
    mvc.perform(get("/api/contacts")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/contacts")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/contacts/export.csv")).andExpect(status().isUnauthorized());
    token("viewer", "Viewer#12345");
  }

  @Test
  void adminCanDoEverythingOnEveryResource() throws Exception {
    String a = "Admin#12345";
    as("admin", a, get("/api/contacts")).andExpect(status().isOk());
    as("admin", a, get("/api/contacts/7")).andExpect(status().isOk());
    as("admin", a, get("/api/contacts/export.csv")).andExpect(status().isOk());
    as("admin", a, post("/api/contacts")).andExpect(status().isOk());
    as("admin", a, put("/api/contacts/7")).andExpect(status().isOk());
    as("admin", a, delete("/api/contacts/7")).andExpect(status().isOk());
    as("admin", a, delete("/api/notes/3")).andExpect(status().isOk());
  }

  @Test
  void salesRepReadsAndWritesContactsButCannotDeleteOrExport() throws Exception {
    String p = "Rep#12345";
    as("rep", p, get("/api/contacts")).andExpect(status().isOk());
    as("rep", p, post("/api/contacts")).andExpect(status().isOk());
    as("rep", p, put("/api/contacts/7")).andExpect(status().isOk());
    as("rep", p, delete("/api/contacts/7")).andExpect(status().isForbidden());
    as("rep", p, get("/api/contacts/export.csv")).andExpect(status().isForbidden());
  }

  @Test
  void resourceWildcardCoversEveryActionOnNotes() throws Exception {
    String p = "Rep#12345";
    as("rep", p, get("/api/notes")).andExpect(status().isOk());
    as("rep", p, post("/api/notes")).andExpect(status().isOk());
    as("rep", p, delete("/api/notes/3")).andExpect(status().isOk());
  }

  @Test
  void viewerReadsAndExportsButCannotWriteOrDelete() throws Exception {
    String p = "Viewer#12345";
    as("viewer", p, get("/api/contacts")).andExpect(status().isOk());
    as("viewer", p, get("/api/contacts/7")).andExpect(status().isOk());
    as("viewer", p, get("/api/contacts/export.csv")).andExpect(status().isOk());
    as("viewer", p, post("/api/contacts")).andExpect(status().isForbidden());
    as("viewer", p, put("/api/contacts/7")).andExpect(status().isForbidden());
    as("viewer", p, delete("/api/contacts/7")).andExpect(status().isForbidden());
    as("viewer", p, get("/api/notes")).andExpect(status().isOk());
    as("viewer", p, post("/api/notes")).andExpect(status().isForbidden());
  }

  @Test
  void exportIsNotImpliedByRead() throws Exception {
    as("rep", "Rep#12345", get("/api/contacts/export.csv")).andExpect(status().isForbidden());
  }

  @Test
  void aRoleMissingFromThePolicyIsDeniedOnProtectedResourcesButNotOnUnmappedPaths() throws Exception {
    String p = "Guest#12345";
    as("guest", p, get("/api/contacts")).andExpect(status().isForbidden());
    as("guest", p, get("/api/notes")).andExpect(status().isForbidden());
    as("guest", p, get("/api/unmapped")).andExpect(status().isOk());
  }

  @Test
  void patchIsTreatedAsWriteSoItIsAllowedThroughToTheHandlerForWritersAndDeniedForReaders() throws Exception {
    // Admin passes the rule; the test app has no PATCH handler, so MVC answers 405 (not 401/403).
    as("admin", "Admin#12345", patch("/api/contacts/7")).andExpect(status().isMethodNotAllowed());
    as("viewer", "Viewer#12345", patch("/api/contacts/7")).andExpect(status().isForbidden());
  }
}
