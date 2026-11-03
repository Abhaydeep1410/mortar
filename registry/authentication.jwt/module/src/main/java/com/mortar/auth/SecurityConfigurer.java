package com.mortar.auth;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * Extension point for the module-owned {@code SecurityFilterChain}. Declare implementations as
 * beans to add rules to the shared {@link HttpSecurity}. They run, in bean order, before the base
 * rules (login and health open, everything else authenticated), so any request matcher they add
 * is evaluated first.
 */
@FunctionalInterface
public interface SecurityConfigurer {

  void configure(HttpSecurity http) throws Exception;
}
