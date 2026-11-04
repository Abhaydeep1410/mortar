package com.mortar.crud;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.util.MultiValueMap;

/**
 * Parses and validates the list parameters {@code page}, {@code size}, {@code sort} and {@code q}.
 * Reads the raw parameter map because Spring would otherwise split {@code sort=name,desc} into two
 * values. Anything unclear is rejected rather than guessed.
 *
 * @param pageable page, size and sort; sorted by {@code id} ascending when no sort is given, so
 *     paging is stable
 * @param q the search text, or {@code null} when absent or blank
 */
public record ListQuery(Pageable pageable, String q) {

  public static ListQuery parse(
      MultiValueMap<String, String> params, CrudProperties properties, Set<String> sortableFields) {
    int page = integer(params, "page", 0);
    int size = integer(params, "size", properties.defaultPageSize());
    if (page < 0) {
      throw new InvalidListQueryException("page must be 0 or greater");
    }
    if (size < 1 || size > properties.maxPageSize()) {
      throw new InvalidListQueryException("size must be between 1 and " + properties.maxPageSize());
    }

    List<Sort.Order> orders = new ArrayList<>();
    for (String value : params.getOrDefault("sort", List.of())) {
      orders.add(order(value, sortableFields));
    }
    Sort sort = orders.isEmpty() ? Sort.by(Sort.Direction.ASC, "id") : Sort.by(orders);

    String q = params.getFirst("q");
    return new ListQuery(PageRequest.of(page, size, sort), q == null || q.isBlank() ? null : q.strip());
  }

  private static int integer(MultiValueMap<String, String> params, String name, int fallback) {
    String raw = params.getFirst(name);
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    try {
      return Integer.parseInt(raw.strip());
    } catch (NumberFormatException e) {
      throw new InvalidListQueryException(name + " must be a whole number");
    }
  }

  private static Sort.Order order(String value, Set<String> sortableFields) {
    String[] parts = value.split(",", -1);
    if (parts.length > 2) {
      throw new InvalidListQueryException("sort must look like <field>,<asc|desc>");
    }
    String field = parts[0].strip();
    if (!sortableFields.contains(field)) {
      throw new InvalidListQueryException(
          "cannot sort by '" + field + "'; sortable fields: " + sortableFields.stream().sorted().toList());
    }
    Sort.Direction direction = Sort.Direction.ASC;
    if (parts.length == 2) {
      switch (parts[1].strip().toLowerCase(Locale.ROOT)) {
        case "asc" -> direction = Sort.Direction.ASC;
        case "desc" -> direction = Sort.Direction.DESC;
        default -> throw new InvalidListQueryException("sort direction must be asc or desc");
      }
    }
    return new Sort.Order(direction, field);
  }
}
