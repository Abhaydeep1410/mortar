package com.mortar.auth;

import java.time.Instant;
import java.util.List;

/**
 * The verified content of a token.
 *
 * @param subject the user id the token was issued for
 * @param roles role names without the {@code ROLE_} prefix
 * @param issuedAt when the token was issued
 * @param expiresAt when the token stops being valid (exclusive)
 */
public record Claims(String subject, List<String> roles, Instant issuedAt, Instant expiresAt) {

  public Claims {
    roles = List.copyOf(roles);
  }
}
