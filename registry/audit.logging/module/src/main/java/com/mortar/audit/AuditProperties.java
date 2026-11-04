package com.mortar.audit;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code mortar.audit}. Invalid values fail application startup.
 *
 * @param entities the entities to audit: URL segment under {@code /api/} and the name recorded.
 *     List only entities that can be written; read-only ones need no audit.
 * @param adminRole the only role allowed to read {@code /api/audit-logs} (without {@code ROLE_})
 */
@ConfigurationProperties(prefix = "mortar.audit")
public record AuditProperties(List<AuditedEntity> entities, @DefaultValue("ADMIN") String adminRole) {

  static final Pattern PATH_SEGMENT = Pattern.compile("[a-z][a-z0-9-]*");
  static final Pattern ENTITY_NAME = Pattern.compile("[a-z][a-z0-9_]*");
  static final Pattern ROLE_NAME = Pattern.compile("[A-Z][A-Z0-9_]*");

  /** URL segment (for example {@code contacts}) and the entity name recorded (for example {@code contact}). */
  public record AuditedEntity(String path, String name) {}

  public AuditProperties {
    if (entities == null || entities.isEmpty()) {
      throw new IllegalArgumentException("mortar.audit.entities must list at least one entity");
    }
    if (adminRole == null || !ROLE_NAME.matcher(adminRole).matches()) {
      throw new IllegalArgumentException("mortar.audit.admin-role must match " + ROLE_NAME.pattern());
    }
    entities = List.copyOf(entities);
    Set<String> paths = new HashSet<>();
    Set<String> names = new HashSet<>();
    for (AuditedEntity entity : entities) {
      if (entity == null || entity.path() == null || !PATH_SEGMENT.matcher(entity.path()).matches()) {
        throw new IllegalArgumentException(
            "mortar.audit.entities[].path must match " + PATH_SEGMENT.pattern() + ": " + entity);
      }
      if (entity.name() == null || !ENTITY_NAME.matcher(entity.name()).matches()) {
        throw new IllegalArgumentException(
            "mortar.audit.entities[].name must match " + ENTITY_NAME.pattern() + ": " + entity);
      }
      if (!paths.add(entity.path()) || !names.add(entity.name())) {
        throw new IllegalArgumentException("duplicate audited path or name: " + entity);
      }
    }
    if (paths.contains("audit-logs")) {
      throw new IllegalArgumentException("audit-logs cannot be audited: it is read-only and owned by this module");
    }
  }
}
