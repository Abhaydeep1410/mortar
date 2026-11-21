package com.reklo.auth;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code reklo.auth.jwt}. Invalid values fail application startup.
 *
 * @param secret HMAC secret, at least 32 bytes (UTF-8). Never logged.
 * @param ttlMinutes token lifetime in minutes, 1 to 1440
 * @param issuer value of the {@code iss} claim; tokens from other issuers are rejected
 * @param allowedOrigins browser origins allowed by CORS for {@code /api/**}
 */
@ConfigurationProperties(prefix = "reklo.auth.jwt")
public record JwtProperties(
    String secret,
    @DefaultValue("60") int ttlMinutes,
    @DefaultValue("reklo") String issuer,
    @DefaultValue("http://localhost:3000") List<String> allowedOrigins) {

  public static final int MIN_SECRET_BYTES = 32;
  public static final int MAX_TTL_MINUTES = 1440;

  public JwtProperties {
    if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
      throw new IllegalArgumentException(
          "reklo.auth.jwt.secret must be at least " + MIN_SECRET_BYTES + " bytes (set JWT_SECRET)");
    }
    if (ttlMinutes < 1 || ttlMinutes > MAX_TTL_MINUTES) {
      throw new IllegalArgumentException(
          "reklo.auth.jwt.ttl-minutes must be between 1 and " + MAX_TTL_MINUTES);
    }
    if (issuer == null || issuer.isBlank()) {
      throw new IllegalArgumentException("reklo.auth.jwt.issuer must not be blank");
    }
    allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
  }
}
