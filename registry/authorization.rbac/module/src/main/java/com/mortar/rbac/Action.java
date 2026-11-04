package com.mortar.rbac;

import java.util.Locale;

/** The four permission actions a role can hold on a resource. */
public enum Action {
  READ,
  WRITE,
  DELETE,
  EXPORT;

  /** The lowercase form used in permission strings, e.g. {@code contact:read}. */
  public String id() {
    return name().toLowerCase(Locale.ROOT);
  }
}
