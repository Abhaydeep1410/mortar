package com.mortar.rbac;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code mortar.auth.rbac}. Invalid values fail application startup, because a
 * silently ignored permission would leave an endpoint less protected than the blueprint says.
 *
 * @param roles role names with their permissions; same shape as the blueprint's {@code roles}
 * @param resources maps a URL path segment to a resource name, e.g. {@code contacts -> contact}
 */
@ConfigurationProperties(prefix = "mortar.auth.rbac")
public record RbacProperties(List<RoleDefinition> roles, List<ResourceMapping> resources) {

  static final Pattern ROLE_NAME = Pattern.compile("[A-Z][A-Z0-9_]*");
  static final Pattern PATH_SEGMENT = Pattern.compile("[a-z][a-z0-9-]*");
  static final Pattern RESOURCE_NAME = Pattern.compile("[a-z][a-z0-9_]*");
  static final Pattern PERMISSION = Pattern.compile("\\*|([a-z][a-z0-9_]*):(read|write|delete|export|\\*)");

  /** One role and the permissions it holds: {@code *}, {@code <resource>:*} or {@code <resource>:<action>}. */
  public record RoleDefinition(String name, List<String> permissions) {}

  /** URL path segment under {@code /api/} and the resource name permissions refer to. */
  public record ResourceMapping(String path, String name) {}

  public RbacProperties {
    if (resources == null || resources.isEmpty()) {
      throw new IllegalArgumentException("mortar.auth.rbac.resources must list at least one resource");
    }
    if (roles == null || roles.isEmpty()) {
      throw new IllegalArgumentException("mortar.auth.rbac.roles must list at least one role");
    }
    resources = List.copyOf(resources);
    roles = List.copyOf(roles);

    Set<String> paths = new HashSet<>();
    Set<String> names = new HashSet<>();
    for (ResourceMapping resource : resources) {
      if (resource == null || resource.path() == null || !PATH_SEGMENT.matcher(resource.path()).matches()) {
        throw new IllegalArgumentException(
            "mortar.auth.rbac.resources[].path must match " + PATH_SEGMENT.pattern() + ": " + resource);
      }
      if (resource.name() == null || !RESOURCE_NAME.matcher(resource.name()).matches()) {
        throw new IllegalArgumentException(
            "mortar.auth.rbac.resources[].name must match " + RESOURCE_NAME.pattern() + ": " + resource);
      }
      if (!paths.add(resource.path()) || !names.add(resource.name())) {
        throw new IllegalArgumentException("duplicate resource path or name: " + resource);
      }
    }

    Set<String> roleNames = new HashSet<>();
    for (RoleDefinition role : roles) {
      if (role == null || role.name() == null || !ROLE_NAME.matcher(role.name()).matches()) {
        throw new IllegalArgumentException(
            "mortar.auth.rbac.roles[].name must match " + ROLE_NAME.pattern() + ": " + role);
      }
      if (!roleNames.add(role.name())) {
        throw new IllegalArgumentException("duplicate role: " + role.name());
      }
      if (role.permissions() == null || role.permissions().isEmpty()) {
        throw new IllegalArgumentException("role " + role.name() + " has no permissions");
      }
      for (String permission : role.permissions()) {
        var matcher = permission == null ? null : PERMISSION.matcher(permission);
        if (matcher == null || !matcher.matches()) {
          throw new IllegalArgumentException(
              "role " + role.name() + ": invalid permission '" + permission
                  + "' (use *, <resource>:*, or <resource>:read|write|delete|export)");
        }
        String resource = matcher.group(1);
        if (resource != null && !names.contains(resource)) {
          throw new IllegalArgumentException(
              "role " + role.name() + ": permission '" + permission + "' names an unknown resource");
        }
      }
    }
  }
}
