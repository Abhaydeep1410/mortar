package com.mortar.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mortar.deploy.testapp.TestApp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Boots a bare application with the module on the classpath and checks what it provides. */
@SpringBootTest(classes = TestApp.class)
class DeploymentIntegrationTest {

  @Autowired private WebApplicationContext context;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private Environment environment;

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).build();
  }

  @Test
  void healthEndpointAnswersUpWithNoDetails() throws Exception {
    mvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.components").doesNotExist())
        .andExpect(jsonPath("$.details").doesNotExist());
  }

  @Test
  void onlyTheHealthEndpointIsExposed() throws Exception {
    mvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
    mvc.perform(get("/actuator/beans")).andExpect(status().isNotFound());
    mvc.perform(get("/actuator/info")).andExpect(status().isNotFound());
  }

  @Test
  void moduleDefaultsAreAppliedButTheApplicationCanOverrideThem() {
    assertThat(environment.getProperty("management.endpoint.health.show-details")).isEqualTo("never");
    assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("update");
    assertThat(environment.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
    assertThat(environment.getProperty("mortar.property.that.does.not.exist")).isNull();
  }

  @Test
  void flywayAppliedTheBaselineMigration() {
    // Flyway also writes a version-less row when it creates the schema; only versioned migrations count.
    List<Map<String, Object>> history =
        jdbc.queryForList("select \"version\", \"success\" from \"flyway_schema_history\" where \"version\" is not null order by \"installed_rank\"");

    assertThat(history).hasSize(1);
    assertThat(history.get(0).get("version")).isEqualTo("0.1");
    assertThat(history.get(0).get("success")).isEqualTo(true);
  }

  @Test
  void auditLogTableHasTheColumnsTheAuditModuleWrites() {
    jdbc.update("delete from audit_log");
    jdbc.update(
        "insert into audit_log (actor, action, entity, entity_id, occurred_at) values (?, ?, ?, ?, ?)",
        "admin", "CREATE", "contact", 7L, OffsetDateTime.parse("2026-11-06T10:00:00Z"));
    jdbc.update(
        "insert into audit_log (actor, action, entity, entity_id, occurred_at) values (?, ?, ?, ?, ?)",
        "admin", "DELETE", "contact", null, OffsetDateTime.parse("2026-11-06T10:01:00Z"));

    List<Map<String, Object>> rows =
        jdbc.queryForList("select id, actor, action, entity, entity_id from audit_log order by id");

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).get("actor")).isEqualTo("admin");
    assertThat(((Number) rows.get(0).get("id")).longValue()).isPositive();
    assertThat(((Number) rows.get(0).get("entity_id")).longValue()).isEqualTo(7L);
    assertThat(rows.get(1).get("entity_id")).isNull();
    jdbc.update("delete from audit_log");
  }

  @Test
  void auditLogRejectsRowsWithoutActorOrTimestamp() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into audit_log (actor, action, entity, entity_id, occurred_at) values (?, ?, ?, ?, ?)",
                    null, "CREATE", "contact", 1L, OffsetDateTime.parse("2026-11-06T10:00:00Z")))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }
}
