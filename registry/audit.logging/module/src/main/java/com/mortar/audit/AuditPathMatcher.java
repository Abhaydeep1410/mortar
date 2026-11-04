package com.mortar.audit;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a request is an audited write and which entity and id it concerns. Only these
 * shapes count: {@code POST /api/<path>}, {@code PUT /api/<path>/<id>}, {@code DELETE /api/<path>/<id>}
 * with a numeric id and a configured path. Everything else is ignored.
 */
final class AuditPathMatcher {

  /** What a matching request is about; {@code idFromPath} is null for CREATE. */
  record Target(String entity, AuditAction action, Long idFromPath) {}

  private static final Pattern PATH = Pattern.compile("^/api/([a-z][a-z0-9-]*)(?:/(\\d{1,18}))?/?$");

  private final Map<String, String> entityByPath = new HashMap<>();

  AuditPathMatcher(AuditProperties properties) {
    for (AuditProperties.AuditedEntity entity : properties.entities()) {
      entityByPath.put(entity.path(), entity.name());
    }
  }

  Optional<Target> match(String method, String path) {
    Matcher matcher = PATH.matcher(path);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    String entity = entityByPath.get(matcher.group(1));
    if (entity == null) {
      return Optional.empty();
    }
    boolean hasId = matcher.group(2) != null;
    Long id = hasId ? Long.valueOf(matcher.group(2)) : null;
    return switch (method) {
      case "POST" -> hasId ? Optional.empty() : Optional.of(new Target(entity, AuditAction.CREATE, null));
      case "PUT" -> hasId ? Optional.of(new Target(entity, AuditAction.UPDATE, id)) : Optional.empty();
      case "DELETE" -> hasId ? Optional.of(new Target(entity, AuditAction.DELETE, id)) : Optional.empty();
      default -> Optional.empty();
    };
  }

  /**
   * The id of a created resource, read from the {@code Location} header of the response: it must
   * point at {@code <requestPath>/<id>}. Returns empty for anything else.
   */
  static Optional<Long> idFromLocation(String location, String requestPath) {
    if (location == null || location.isBlank()) {
      return Optional.empty();
    }
    String path;
    try {
      path = URI.create(location).getPath();
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    String base = requestPath.endsWith("/") ? requestPath : requestPath + "/";
    if (path == null || !path.startsWith(base)) {
      return Optional.empty();
    }
    String rest = path.substring(base.length());
    return rest.matches("\\d{1,18}") ? Optional.of(Long.valueOf(rest)) : Optional.empty();
  }
}
