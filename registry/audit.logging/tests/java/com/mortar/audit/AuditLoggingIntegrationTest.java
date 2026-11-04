package com.mortar.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.mortar.audit.testapp.AuditTestApp;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Both modules and a real database, through HTTP: writes are recorded with the signed-in actor,
 * reads and failures are not, and only the admin role can read the log.
 */
@SpringBootTest(
    classes = AuditTestApp.class,
    properties = {
      "mortar.auth.jwt.secret=integration-secret-integration-secret-012345",
      "mortar.auth.jwt.issuer=audit-integration",
      "mortar.audit.entities[0].path=contacts",
      "mortar.audit.entities[0].name=contact",
      "mortar.audit.entities[1].path=deals",
      "mortar.audit.entities[1].name=deal"
    })
class AuditLoggingIntegrationTest {

  @Autowired private WebApplicationContext context;
  @Autowired private AuditLogRepository audit;

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    audit.deleteAll();
  }

  private String token(String username, String password) throws Exception {
    String body =
        mvc.perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.token");
  }

  private ResultActions asAdmin(MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(request.header("Authorization", "Bearer " + token("admin", "Admin#12345")));
  }

  private ResultActions asViewer(MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(request.header("Authorization", "Bearer " + token("viewer", "Viewer#12345")));
  }

  @Test
  void createUpdateAndDeleteAreRecordedWithActorActionEntityIdAndTime() throws Exception {
    String location =
        asAdmin(post("/api/contacts")).andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location");
    long createdId = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    asViewer(put("/api/contacts/55")).andExpect(status().isOk());
    asAdmin(delete("/api/contacts/55")).andExpect(status().isNoContent());

    List<AuditLog> records = audit.findAll(Sort.by("id"));
    assertThat(records).hasSize(3);
    assertThat(records).extracting(AuditLog::getActor).containsExactly("admin", "viewer", "admin");
    assertThat(records)
        .extracting(AuditLog::getAction)
        .containsExactly(AuditAction.CREATE, AuditAction.UPDATE, AuditAction.DELETE);
    assertThat(records).extracting(AuditLog::getEntity).containsOnly("contact");
    assertThat(records).extracting(AuditLog::getEntityId).containsExactly(createdId, 55L, 55L);
    assertThat(records).extracting(AuditLog::getTimestamp).doesNotContainNull();
  }

  @Test
  void theCreatedIdComesFromTheLocationHeaderOfEachEntity() throws Exception {
    asAdmin(post("/api/deals")).andExpect(status().isCreated());

    AuditLog record = audit.findAll().get(0);
    assertThat(record.getEntity()).isEqualTo("deal");
    assertThat(record.getEntityId()).isEqualTo(7L);
  }

  @Test
  void readsFailuresRejectionsAndUnconfiguredPathsAreNotRecorded() throws Exception {
    asAdmin(get("/api/contacts")).andExpect(status().isOk());
    asAdmin(post("/api/contacts").param("fail", "yes")).andExpect(status().isBadRequest());
    asAdmin(delete("/api/contacts/404")).andExpect(status().isNotFound());
    asAdmin(put("/api/contacts/404")).andExpect(status().isNotFound());
    asAdmin(post("/api/unaudited")).andExpect(status().isOk());
    mvc.perform(post("/api/contacts")).andExpect(status().isUnauthorized());
    mvc.perform(delete("/api/contacts/1").header("Authorization", "Bearer garbage")).andExpect(status().isUnauthorized());

    assertThat(audit.count()).isZero();
  }

  @Test
  void adminListsNewestFirstWithTheContractShape() throws Exception {
    asAdmin(post("/api/contacts")).andExpect(status().isCreated());
    asAdmin(put("/api/contacts/5")).andExpect(status().isOk());
    asAdmin(delete("/api/contacts/5")).andExpect(status().isNoContent());

    asAdmin(get("/api/audit-logs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(3))
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(20))
        .andExpect(jsonPath("$.content.length()").value(3))
        .andExpect(jsonPath("$.content[0].action").value("DELETE"))
        .andExpect(jsonPath("$.content[2].action").value("CREATE"))
        .andExpect(jsonPath("$.content[0].actor").value("admin"))
        .andExpect(jsonPath("$.content[0].entity").value("contact"))
        .andExpect(jsonPath("$.content[0].entityId").value(5))
        .andExpect(jsonPath("$.content[0].timestamp").isNotEmpty())
        .andExpect(jsonPath("$.content[0].id").isNotEmpty());
  }

  @Test
  void listIsPagedAndFilterable() throws Exception {
    for (int i = 0; i < 5; i++) {
      asAdmin(post("/api/contacts")).andExpect(status().isCreated());
    }
    asViewer(delete("/api/contacts/9")).andExpect(status().isNoContent());
    asAdmin(post("/api/deals")).andExpect(status().isCreated());

    asAdmin(get("/api/audit-logs").param("page", "1").param("size", "3"))
        .andExpect(jsonPath("$.content.length()").value(3))
        .andExpect(jsonPath("$.totalElements").value(7))
        .andExpect(jsonPath("$.page").value(1))
        .andExpect(jsonPath("$.size").value(3));
    asAdmin(get("/api/audit-logs").param("entity", "deal")).andExpect(jsonPath("$.totalElements").value(1));
    asAdmin(get("/api/audit-logs").param("actor", "viewer"))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].entityId").value(9));
    asAdmin(get("/api/audit-logs").param("entity", "contact").param("entityId", "9"))
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test
  void badListParametersAreBadRequestsWithAMessage() throws Exception {
    asAdmin(get("/api/audit-logs").param("page", "-1")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isNotEmpty());
    asAdmin(get("/api/audit-logs").param("size", "0")).andExpect(status().isBadRequest());
    asAdmin(get("/api/audit-logs").param("size", "101")).andExpect(status().isBadRequest());
    asAdmin(get("/api/audit-logs").param("entityId", "abc"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").isNotEmpty());
  }

  @Test
  void onlyTheAdminRoleCanReadAndNobodyCanWriteTheLog() throws Exception {
    mvc.perform(get("/api/audit-logs")).andExpect(status().isUnauthorized());
    asViewer(get("/api/audit-logs")).andExpect(status().isForbidden());
    asAdmin(post("/api/audit-logs")).andExpect(status().isForbidden());
    asAdmin(put("/api/audit-logs/1")).andExpect(status().isForbidden());
    asAdmin(delete("/api/audit-logs/1")).andExpect(status().isForbidden());
    asAdmin(patch("/api/audit-logs")).andExpect(status().isForbidden());
    assertThat(audit.count()).isZero();
  }
}
