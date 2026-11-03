package com.mortar.auth;

/**
 * Thrown by {@link JwtService#validate(String)} for any token that must not be trusted. The message
 * names the category of failure only and never contains token content.
 */
public class InvalidTokenException extends RuntimeException {

  public InvalidTokenException(String message) {
    super(message);
  }

  public InvalidTokenException(String message, Throwable cause) {
    super(message, cause);
  }
}
