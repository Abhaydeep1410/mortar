package com.mortar.crud;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Per-entity choices the application makes when it subclasses {@link CrudController}.
 *
 * @param sortableFields entity properties a client may sort by ({@code id} is always allowed)
 * @param searchFields string properties the {@code q} parameter searches, case-insensitively
 * @param readOnly when true, POST, PUT and DELETE answer 405
 */
public record CrudSettings(Set<String> sortableFields, Set<String> searchFields, boolean readOnly) {

  public CrudSettings {
    Set<String> sortable = new LinkedHashSet<>(sortableFields);
    sortable.add("id");
    sortableFields = Set.copyOf(sortable);
    searchFields = Set.copyOf(searchFields);
  }

  public static CrudSettings of(Set<String> sortableFields, Set<String> searchFields) {
    return new CrudSettings(sortableFields, searchFields, false);
  }

  public CrudSettings asReadOnly() {
    return new CrudSettings(sortableFields, searchFields, true);
  }
}
