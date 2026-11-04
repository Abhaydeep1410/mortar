package com.mortar.rbac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mortar.rbac.RbacProperties.ResourceMapping;
import com.mortar.rbac.RbacProperties.RoleDefinition;
import java.util.List;
import org.junit.jupiter.api.Test;

class PermissionPolicyTest {

  private static final List<ResourceMapping> RESOURCES =
      List.of(new ResourceMapping("contacts", "contact"), new ResourceMapping("notes", "note"));

  private static RbacProperties props(RoleDefinition... roles) {
    return new RbacProperties(List.of(roles), RESOURCES);
  }

  private static RoleDefinition role(String name, String... permissions) {
    return new RoleDefinition(name, List.of(permissions));
  }

  private final PermissionPolicy policy =
      new PermissionPolicy(
          props(
              role("ADMIN", "*"),
              role("SALES_REP", "contact:read", "contact:write", "note:*"),
              role("VIEWER", "contact:read", "note:read", "contact:export")));

  @Test
  void globalWildcardGrantsEverything() {
    for (Action action : Action.values()) {
      assertThat(policy.allows(List.of("ADMIN"), "contact", action)).isTrue();
      assertThat(policy.allows(List.of("ADMIN"), "note", action)).isTrue();
    }
  }

  @Test
  void resourceWildcardGrantsEveryActionOnThatResourceOnly() {
    for (Action action : Action.values()) {
      assertThat(policy.allows(List.of("SALES_REP"), "note", action)).isTrue();
    }
    assertThat(policy.allows(List.of("SALES_REP"), "contact", Action.DELETE)).isFalse();
  }

  @Test
  void writeDoesNotImplyReadOrDelete() {
    PermissionPolicy writeOnly = new PermissionPolicy(props(role("INGEST", "contact:write")));
    assertThat(writeOnly.allows(List.of("INGEST"), "contact", Action.WRITE)).isTrue();
    assertThat(writeOnly.allows(List.of("INGEST"), "contact", Action.READ)).isFalse();
    assertThat(writeOnly.allows(List.of("INGEST"), "contact", Action.DELETE)).isFalse();
  }

  @Test
  void unknownRolesAreDeniedAndAnyOfSeveralRolesCanGrant() {
    assertThat(policy.allows(List.of("GUEST"), "contact", Action.READ)).isFalse();
    assertThat(policy.allows(List.of(), "contact", Action.READ)).isFalse();
    assertThat(policy.allows(List.of("GUEST", "VIEWER"), "contact", Action.READ)).isTrue();
  }

  @Test
  void rolesForListsHoldersSortedAndEmptyWhenNobodyHoldsIt() {
    assertThat(policy.rolesFor("contact", Action.READ)).containsExactly("ADMIN", "SALES_REP", "VIEWER");
    assertThat(policy.rolesFor("contact", Action.EXPORT)).containsExactly("ADMIN", "VIEWER");
    assertThat(policy.rolesFor("note", Action.DELETE)).containsExactly("ADMIN", "SALES_REP");
    assertThat(new PermissionPolicy(props(role("VIEWER", "contact:read"))).rolesFor("note", Action.READ))
        .isEmpty();
  }

  @Test
  void rulesSeparateExportFromReadAndEndWithADenyAllPerResource() {
    List<RbacRules.Rule> rules = RbacRules.of(policy);

    assertThat(rules).hasSize(16);
    RbacRules.Rule export = rules.get(0);
    assertThat(export.patterns()).containsExactly("/api/contacts/export.csv");
    assertThat(export.roles()).containsExactly("ADMIN", "VIEWER");
    RbacRules.Rule catchAll = rules.get(7);
    assertThat(catchAll.method()).isNull();
    assertThat(catchAll.roles()).isEmpty();
    assertThat(rules.get(8).patterns()).containsExactly("/api/notes/export.csv");
  }

  @Test
  void invalidConfigurationFailsFast() {
    assertThatThrownBy(() -> new RbacProperties(null, RESOURCES)).hasMessageContaining("roles");
    assertThatThrownBy(() -> new RbacProperties(List.of(role("A", "*")), List.of()))
        .hasMessageContaining("resources");
    assertThatThrownBy(() -> props(role("admin", "*"))).hasMessageContaining("name");
    assertThatThrownBy(() -> props(role("A", "*"), role("A", "contact:read"))).hasMessageContaining("duplicate role");
    assertThatThrownBy(() -> props(role("A"))).hasMessageContaining("no permissions");
    assertThatThrownBy(() -> props(role("A", "contact:purge"))).hasMessageContaining("invalid permission");
    assertThatThrownBy(() -> props(role("A", "contacts:read"))).hasMessageContaining("unknown resource");
    assertThatThrownBy(
            () ->
                new RbacProperties(
                    List.of(role("A", "*")),
                    List.of(new ResourceMapping("contacts", "contact"), new ResourceMapping("contacts", "other"))))
        .hasMessageContaining("duplicate resource");
    assertThatThrownBy(
            () -> new RbacProperties(List.of(role("A", "*")), List.of(new ResourceMapping("Con/tacts", "contact"))))
        .hasMessageContaining("path");
  }
}
