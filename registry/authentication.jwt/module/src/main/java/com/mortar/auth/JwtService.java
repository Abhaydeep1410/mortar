package com.mortar.auth;

import java.util.Collection;

/** Issues and validates signed JWTs. */
public interface JwtService {

  /**
   * Issues a signed token.
   *
   * @param userId subject of the token, must not be blank
   * @param roles role names without the {@code ROLE_} prefix, may be empty
   * @throws IllegalArgumentException if {@code userId} is blank or {@code roles} is null
   */
  String issue(String userId, Collection<String> roles);

  /**
   * Verifies signature, algorithm, issuer and expiry, then returns the claims.
   *
   * @throws InvalidTokenException if the token is malformed, forged, expired or from another issuer
   */
  Claims validate(String token);
}
