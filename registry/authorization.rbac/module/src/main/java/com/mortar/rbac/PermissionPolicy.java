package com.mortar.rbac;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Answers "may these roles perform this action on this resource?". Anything not granted is
 * denied. Role names are given without the {@code ROLE_} prefix.
 *
 * <p>Permissions are exact: {@code contact:write} does not imply {@code contact:read}.
 */
public final class PermissionPolicy {

  private final Map<String, Set<String>> permissionsByRole;
  private final List<RbacProperties.ResourceMapping> resources;

  public PermissionPolicy(RbacProperties properties) {
    this.resources = properties.resources();
    this.permissionsByRole =
        properties.roles().stream()
            .collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                    RbacProperties.RoleDefinition::name, r -> Set.copyOf(r.permissions())));
  }

  /** True when at least one of the roles holds the permission. */
  public boolean allows(Collection<String> roles, String resource, Action action) {
    return roles.stream().anyMatch(role -> roleAllows(role, resource, action));
  }

  /** Sorted names of every role holding the permission. */
  public List<String> rolesFor(String resource, Action action) {
    Set<String> result = new TreeSet<>();
    for (String role : permissionsByRole.keySet()) {
      if (roleAllows(role, resource, action)) {
        result.add(role);
      }
    }
    return List.copyOf(result);
  }

  /** The resources this policy protects, in configuration order. */
  public List<RbacProperties.ResourceMapping> resources() {
    return resources;
  }

  private boolean roleAllows(String role, String resource, Action action) {
    Set<String> granted = permissionsByRole.get(role);
    return granted != null
        && (granted.contains("*")
            || granted.contains(resource + ":*")
            || granted.contains(resource + ":" + action.id()));
  }
}
