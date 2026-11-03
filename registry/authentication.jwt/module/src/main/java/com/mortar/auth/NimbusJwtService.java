package com.mortar.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.List;

/** HS256 implementation of {@link JwtService} backed by Nimbus JOSE + JWT. */
public final class NimbusJwtService implements JwtService {

  private static final JWSAlgorithm ALGORITHM = JWSAlgorithm.HS256;
  private static final String ROLES_CLAIM = "roles";

  private final MACSigner signer;
  private final MACVerifier verifier;
  private final String issuer;
  private final Duration ttl;
  private final Clock clock;

  public NimbusJwtService(JwtProperties properties, Clock clock) {
    byte[] secret = properties.secret().getBytes(StandardCharsets.UTF_8);
    try {
      this.signer = new MACSigner(secret);
      this.verifier = new MACVerifier(secret);
    } catch (JOSEException e) {
      throw new IllegalArgumentException("mortar.auth.jwt.secret is not usable as an HS256 key", e);
    }
    this.issuer = properties.issuer();
    this.ttl = Duration.ofMinutes(properties.ttlMinutes());
    this.clock = clock;
  }

  @Override
  public String issue(String userId, Collection<String> roles) {
    if (userId == null || userId.isBlank()) {
      throw new IllegalArgumentException("userId must not be blank");
    }
    if (roles == null) {
      throw new IllegalArgumentException("roles must not be null");
    }
    Instant now = clock.instant();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .subject(userId)
            .issuer(issuer)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(ttl)))
            .claim(ROLES_CLAIM, List.copyOf(roles))
            .build();
    SignedJWT jwt = new SignedJWT(new JWSHeader(ALGORITHM), claims);
    try {
      jwt.sign(signer);
    } catch (JOSEException e) {
      throw new IllegalStateException("could not sign token", e);
    }
    return jwt.serialize();
  }

  @Override
  public Claims validate(String token) {
    if (token == null || token.isBlank()) {
      throw new InvalidTokenException("missing token");
    }
    SignedJWT jwt;
    try {
      jwt = SignedJWT.parse(token);
    } catch (ParseException e) {
      throw new InvalidTokenException("malformed token", e);
    }
    if (!ALGORITHM.equals(jwt.getHeader().getAlgorithm())) {
      throw new InvalidTokenException("unexpected signing algorithm");
    }
    try {
      if (!jwt.verify(verifier)) {
        throw new InvalidTokenException("bad signature");
      }
    } catch (JOSEException e) {
      throw new InvalidTokenException("signature could not be verified", e);
    }

    JWTClaimsSet claims;
    List<String> roles;
    try {
      claims = jwt.getJWTClaimsSet();
      roles = claims.getStringListClaim(ROLES_CLAIM);
    } catch (ParseException e) {
      throw new InvalidTokenException("malformed claims", e);
    }
    if (!issuer.equals(claims.getIssuer())) {
      throw new InvalidTokenException("unexpected issuer");
    }
    String subject = claims.getSubject();
    if (subject == null || subject.isBlank()) {
      throw new InvalidTokenException("missing subject");
    }
    Date expiry = claims.getExpirationTime();
    Date issuedAt = claims.getIssueTime();
    if (expiry == null || issuedAt == null) {
      throw new InvalidTokenException("missing time claims");
    }
    if (!clock.instant().isBefore(expiry.toInstant())) {
      throw new InvalidTokenException("token expired");
    }
    return new Claims(
        subject, roles == null ? List.of() : roles, issuedAt.toInstant(), expiry.toInstant());
  }
}
