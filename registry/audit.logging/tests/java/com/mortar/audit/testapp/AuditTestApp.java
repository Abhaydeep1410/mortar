package com.mortar.audit.testapp;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/** Plays the part of a generated application: it supplies users and endpoints, nothing else. */
@SpringBootApplication
public class AuditTestApp {

  @Bean
  UserDetailsService users() {
    var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    return new InMemoryUserDetailsManager(
        User.withUsername("admin").password(encoder.encode("Admin#12345")).roles("ADMIN").build(),
        User.withUsername("viewer").password(encoder.encode("Viewer#12345")).roles("VIEWER").build());
  }
}
