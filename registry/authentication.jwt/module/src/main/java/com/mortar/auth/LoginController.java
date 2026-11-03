package com.mortar.auth;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /api/auth/login}: exchanges credentials for a token. */
@RestController
public class LoginController {

  private static final String ROLE_PREFIX = "ROLE_";

  public record LoginRequest(String username, String password) {}

  public record LoginResponse(String token) {}

  private final AuthenticationManager authenticationManager;
  private final JwtService jwtService;

  public LoginController(AuthenticationManager authenticationManager, JwtService jwtService) {
    this.authenticationManager = authenticationManager;
    this.jwtService = jwtService;
  }

  @PostMapping("/api/auth/login")
  public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
    if (isBlank(request.username()) || isBlank(request.password())) {
      return ResponseEntity.badRequest().build();
    }
    try {
      Authentication authentication =
          authenticationManager.authenticate(
              UsernamePasswordAuthenticationToken.unauthenticated(
                  request.username(), request.password()));
      List<String> roles =
          authentication.getAuthorities().stream()
              .map(GrantedAuthority::getAuthority)
              .filter(authority -> authority.startsWith(ROLE_PREFIX))
              .map(authority -> authority.substring(ROLE_PREFIX.length()))
              .sorted()
              .toList();
      return ResponseEntity.ok(new LoginResponse(jwtService.issue(authentication.getName(), roles)));
    } catch (AuthenticationException e) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
