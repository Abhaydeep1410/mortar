package com.mortar.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NimbusJwtServiceTest {

  private static final String SECRET = "unit-test-secret-unit-test-secret-0123456789";
  private static final String LONG_SECRET = "x".repeat(64);
  private static final Instant START = Instant.parse("2026-11-02T10:00:00Z");

  private MutableClock clock;
  private NimbusJwtService service;

  @BeforeEach
  void setUp() {
    clock = new MutableClock(START);
    service = serviceWith(SECRET, 60, "mortar");
  }

  private NimbusJwtService serviceWith(String secret, int ttlMinutes, String issuer) {
    return new NimbusJwtService(new JwtProperties(secret, ttlMinutes, issuer, List.of()), clock);
  }

  private static JWTClaimsSet.Builder claims(String subject, String issuer, Instant expiry) {
    return new JWTClaimsSet.Builder()
        .subject(subject)
        .issuer(issuer)
        .issueTime(Date.from(START))
        .expirationTime(Date.from(expiry));
  }

  @Test
  void roundTripKeepsSubjectRolesAndTimes() {
    String token = service.issue("alice", List.of("ADMIN", "SALES_REP"));

    Claims claims = service.validate(token);

    assertThat(claims.subject()).isEqualTo("alice");
    assertThat(claims.roles()).containsExactly("ADMIN", "SALES_REP");
    assertThat(claims.issuedAt()).isEqualTo(START);
    assertThat(claims.expiresAt()).isEqualTo(START.plus(Duration.ofMinutes(60)));
  }

  @Test
  void emptyRolesRoundTripToEmptyList() {
    assertThat(service.validate(service.issue("alice", List.of())).roles()).isEmpty();
  }

  @Test
  void tokenIsValidJustBeforeExpiryAndInvalidAtExpiry() {
    String token = service.issue("alice", List.of());

    clock.advance(Duration.ofMinutes(60).minusSeconds(1));
    assertThat(service.validate(token).subject()).isEqualTo("alice");

    clock.advance(Duration.ofSeconds(1));
    assertThatThrownBy(() -> service.validate(token))
        .isInstanceOf(InvalidTokenException.class)
        .hasMessageContaining("expired");
  }

  @Test
  void tokenSignedWithAnotherSecretIsRejected() {
    String foreign = serviceWith("another-secret-another-secret-0123456789", 60, "mortar").issue("alice", List.of());

    assertThatThrownBy(() -> service.validate(foreign))
        .isInstanceOf(InvalidTokenException.class)
        .hasMessageContaining("signature");
  }

  @Test
  void tokenFromAnotherIssuerIsRejected() {
    String foreign = serviceWith(SECRET, 60, "someone-else").issue("alice", List.of());

    assertThatThrownBy(() -> service.validate(foreign))
        .isInstanceOf(InvalidTokenException.class)
        .hasMessageContaining("issuer");
  }

  @Test
  void tamperedPayloadIsRejected() throws Exception {
    String[] parts = service.issue("alice", List.of("VIEWER")).split("\\.");
    JWTClaimsSet forged = claims("alice", "mortar", START.plusSeconds(3600)).claim("roles", List.of("ADMIN")).build();
    String tampered = parts[0] + "." + Base64URL.encode(forged.toString()) + "." + parts[2];

    assertThatThrownBy(() -> service.validate(tampered)).isInstanceOf(InvalidTokenException.class);
  }

  @Test
  void unsecuredAlgNoneTokenIsRejected() {
    String none = new PlainJWT(claims("admin", "mortar", START.plusSeconds(3600)).build()).serialize();

    assertThatThrownBy(() -> service.validate(none)).isInstanceOf(InvalidTokenException.class);
  }

  @Test
  void tokenSignedWithDifferentHmacAlgorithmIsRejectedEvenWithTheRightSecret() throws Exception {
    NimbusJwtService longSecretService = serviceWith(LONG_SECRET, 60, "mortar");
    SignedJWT hs384 =
        new SignedJWT(new JWSHeader(JWSAlgorithm.HS384), claims("alice", "mortar", START.plusSeconds(3600)).build());
    hs384.sign(new MACSigner(LONG_SECRET.getBytes(StandardCharsets.UTF_8)));

    assertThatThrownBy(() -> longSecretService.validate(hs384.serialize()))
        .isInstanceOf(InvalidTokenException.class)
        .hasMessageContaining("algorithm");
  }

  @Test
  void tokenWithoutRolesClaimHasNoRoles() throws Exception {
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims("alice", "mortar", START.plusSeconds(3600)).build());
    jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));

    assertThat(service.validate(jwt.serialize()).roles()).isEmpty();
  }

  @Test
  void tokenWithoutExpiryIsRejected() throws Exception {
    JWTClaimsSet noExpiry = new JWTClaimsSet.Builder().subject("alice").issuer("mortar").issueTime(Date.from(START)).build();
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), noExpiry);
    jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));

    assertThatThrownBy(() -> service.validate(jwt.serialize())).isInstanceOf(InvalidTokenException.class);
  }

  @Test
  void garbageAndBlankTokensAreRejected() {
    for (String bad : new String[] {null, "", "   ", "not-a-token", "a.b.c", "Bearer abc"}) {
      assertThatThrownBy(() -> service.validate(bad)).isInstanceOf(InvalidTokenException.class);
    }
  }

  @Test
  void issueRejectsBlankUserAndNullRoles() {
    assertThatThrownBy(() -> service.issue(" ", List.of())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.issue(null, List.of())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.issue("alice", null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void exceptionMessagesNeverContainTheToken() {
    String token = service.issue("alice", List.of());
    String tampered = token.substring(0, token.length() - 4) + "AAAA";

    assertThatThrownBy(() -> service.validate(tampered))
        .isInstanceOf(InvalidTokenException.class)
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain(tampered).doesNotContain(SECRET));
  }

  @Test
  void propertiesRejectWeakOrInvalidSettings() {
    assertThatThrownBy(() -> new JwtProperties("too-short", 60, "mortar", List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("32 bytes")
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("too-short"));
    assertThatThrownBy(() -> new JwtProperties(null, 60, "mortar", List.of())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new JwtProperties(SECRET, 0, "mortar", List.of())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new JwtProperties(SECRET, 1441, "mortar", List.of())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new JwtProperties(SECRET, 60, " ", List.of())).isInstanceOf(IllegalArgumentException.class);
    assertThat(new JwtProperties(SECRET, 60, "mortar", null).allowedOrigins()).isEmpty();
  }
}
