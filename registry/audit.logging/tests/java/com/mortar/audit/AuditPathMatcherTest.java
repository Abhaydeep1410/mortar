package com.mortar.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mortar.audit.AuditProperties.AuditedEntity;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AuditPathMatcherTest {

  private final AuditPathMatcher matcher =
      new AuditPathMatcher(
          new AuditProperties(
              List.of(new AuditedEntity("contacts", "contact"), new AuditedEntity("deals", "deal")), "ADMIN"));

  @Test
  void createUpdateAndDeleteAreRecognisedWithTheEntityName() {
    assertThat(matcher.match("POST", "/api/contacts"))
        .contains(new AuditPathMatcher.Target("contact", AuditAction.CREATE, null));
    assertThat(matcher.match("PUT", "/api/deals/42"))
        .contains(new AuditPathMatcher.Target("deal", AuditAction.UPDATE, 42L));
    assertThat(matcher.match("DELETE", "/api/contacts/7"))
        .contains(new AuditPathMatcher.Target("contact", AuditAction.DELETE, 7L));
  }

  @Test
  void aTrailingSlashIsTolerated() {
    assertThat(matcher.match("POST", "/api/contacts/")).isPresent();
    assertThat(matcher.match("DELETE", "/api/contacts/7/")).isPresent();
  }

  @Test
  void readsAndOtherMethodsAreIgnored() {
    for (String method : new String[] {"GET", "HEAD", "OPTIONS", "PATCH", "TRACE"}) {
      assertThat(matcher.match(method, "/api/contacts/7")).isEmpty();
      assertThat(matcher.match(method, "/api/contacts")).isEmpty();
    }
  }

  @Test
  void methodAndIdMustAgree() {
    assertThat(matcher.match("POST", "/api/contacts/7")).isEmpty();
    assertThat(matcher.match("PUT", "/api/contacts")).isEmpty();
    assertThat(matcher.match("DELETE", "/api/contacts")).isEmpty();
  }

  @Test
  void unknownPathsNonNumericIdsAndNestedPathsAreIgnored() {
    assertThat(matcher.match("POST", "/api/other")).isEmpty();
    assertThat(matcher.match("DELETE", "/api/contacts/abc")).isEmpty();
    assertThat(matcher.match("DELETE", "/api/contacts/7/notes/3")).isEmpty();
    assertThat(matcher.match("POST", "/api/auth/login")).isEmpty();
    assertThat(matcher.match("POST", "/contacts")).isEmpty();
    assertThat(matcher.match("DELETE", "/api/contacts/-1")).isEmpty();
  }

  @Test
  void createdIdIsReadFromTheLocationHeaderOnlyWhenItPointsAtTheNewResource() {
    assertThat(AuditPathMatcher.idFromLocation("http://localhost/api/contacts/15", "/api/contacts")).contains(15L);
    assertThat(AuditPathMatcher.idFromLocation("/api/contacts/15", "/api/contacts/")).contains(15L);
    assertThat(AuditPathMatcher.idFromLocation(null, "/api/contacts")).isEqualTo(Optional.empty());
    assertThat(AuditPathMatcher.idFromLocation("", "/api/contacts")).isEmpty();
    assertThat(AuditPathMatcher.idFromLocation("/api/deals/15", "/api/contacts")).isEmpty();
    assertThat(AuditPathMatcher.idFromLocation("/api/contacts/abc", "/api/contacts")).isEmpty();
    assertThat(AuditPathMatcher.idFromLocation("/api/contacts/15/extra", "/api/contacts")).isEmpty();
    assertThat(AuditPathMatcher.idFromLocation("http://bad host/x", "/api/contacts")).isEmpty();
  }

  @Test
  void propertiesRejectNonsense() {
    AuditedEntity ok = new AuditedEntity("contacts", "contact");
    assertThatThrownBy(() -> new AuditProperties(null, "ADMIN")).hasMessageContaining("entities");
    assertThatThrownBy(() -> new AuditProperties(List.of(), "ADMIN")).hasMessageContaining("entities");
    assertThatThrownBy(() -> new AuditProperties(List.of(ok), "admin")).hasMessageContaining("admin-role");
    assertThatThrownBy(() -> new AuditProperties(List.of(ok, new AuditedEntity("contacts", "x")), "ADMIN"))
        .hasMessageContaining("duplicate");
    assertThatThrownBy(() -> new AuditProperties(List.of(new AuditedEntity("Bad/Path", "x")), "ADMIN"))
        .hasMessageContaining("path");
    assertThatThrownBy(() -> new AuditProperties(List.of(new AuditedEntity("audit-logs", "x")), "ADMIN"))
        .hasMessageContaining("audit-logs");
  }
}
