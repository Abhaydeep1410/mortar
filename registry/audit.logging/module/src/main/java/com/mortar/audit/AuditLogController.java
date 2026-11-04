package com.mortar.audit;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * {@code GET /api/audit-logs?page=&size=&entity=&entityId=&actor=}, newest first. Restricted to the
 * admin role by the security rules this module adds; there is no write endpoint.
 */
@RestController
class AuditLogController {

  static final int DEFAULT_SIZE = 20;
  static final int MAX_SIZE = 100;

  /** The same list shape as the rest of the API. */
  public record AuditPage(List<AuditLog> content, long totalElements, int page, int size) {}

  public record Error(String message) {}

  private final AuditLogRepository repository;

  AuditLogController(AuditLogRepository repository) {
    this.repository = repository;
  }

  @GetMapping("/api/audit-logs")
  AuditPage list(
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "" + DEFAULT_SIZE) int size,
      @RequestParam(name = "entity", required = false) String entity,
      @RequestParam(name = "entityId", required = false) Long entityId,
      @RequestParam(name = "actor", required = false) String actor) {
    if (page < 0) {
      throw new IllegalArgumentException("page must be 0 or greater");
    }
    if (size < 1 || size > MAX_SIZE) {
      throw new IllegalArgumentException("size must be between 1 and " + MAX_SIZE);
    }
    Specification<AuditLog> filter =
        (root, query, builder) -> {
          List<Predicate> predicates = new ArrayList<>();
          if (entity != null) {
            predicates.add(builder.equal(root.get("entity"), entity));
          }
          if (entityId != null) {
            predicates.add(builder.equal(root.get("entityId"), entityId));
          }
          if (actor != null) {
            predicates.add(builder.equal(root.get("actor"), actor));
          }
          return builder.and(predicates.toArray(new Predicate[0]));
        };
    Sort newestFirst = Sort.by(Sort.Order.desc("timestamp"), Sort.Order.desc("id"));
    Page<AuditLog> result = repository.findAll(filter, PageRequest.of(page, size, newestFirst));
    return new AuditPage(result.getContent(), result.getTotalElements(), result.getNumber(), result.getSize());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Error> badRequest(IllegalArgumentException e) {
    return ResponseEntity.badRequest().body(new Error(e.getMessage()));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<Error> badType(MethodArgumentTypeMismatchException e) {
    return ResponseEntity.badRequest().body(new Error(e.getName() + " must be a whole number"));
  }
}
