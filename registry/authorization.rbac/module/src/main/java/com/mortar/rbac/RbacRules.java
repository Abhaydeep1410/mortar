package com.mortar.rbac;

import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpMethod;

/**
 * Turns a {@link PermissionPolicy} into an ordered list of request rules. Order matters: the first
 * matching rule wins, so the narrow export path comes before the general read rule, and each
 * resource ends with a catch-all that denies every method not mapped to an action.
 *
 * <p>HTTP mapping: GET and HEAD read; POST, PUT and PATCH write; DELETE delete;
 * {@code GET /api/<path>/export.csv} export. Only mapped resources are touched; other
 * {@code /api/**} paths fall through to the base rule (any authenticated user).
 */
final class RbacRules {

  /** {@code method == null} means any method. */
  record Rule(HttpMethod method, String[] patterns, List<String> roles) {}

  private RbacRules() {}

  static List<Rule> of(PermissionPolicy policy) {
    List<Rule> rules = new ArrayList<>();
    for (RbacProperties.ResourceMapping resource : policy.resources()) {
      String base = "/api/" + resource.path();
      String[] all = {base, base + "/**"};
      String name = resource.name();

      rules.add(new Rule(HttpMethod.GET, new String[] {base + "/export.csv"}, policy.rolesFor(name, Action.EXPORT)));
      List<String> readers = policy.rolesFor(name, Action.READ);
      rules.add(new Rule(HttpMethod.GET, all, readers));
      rules.add(new Rule(HttpMethod.HEAD, all, readers));
      List<String> writers = policy.rolesFor(name, Action.WRITE);
      rules.add(new Rule(HttpMethod.POST, all, writers));
      rules.add(new Rule(HttpMethod.PUT, all, writers));
      rules.add(new Rule(HttpMethod.PATCH, all, writers));
      rules.add(new Rule(HttpMethod.DELETE, all, policy.rolesFor(name, Action.DELETE)));
      rules.add(new Rule(null, all, List.of()));
    }
    return List.copyOf(rules);
  }
}
