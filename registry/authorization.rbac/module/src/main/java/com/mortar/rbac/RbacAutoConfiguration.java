package com.mortar.rbac;

import com.mortar.auth.JwtAuthAutoConfiguration;
import com.mortar.auth.SecurityConfigurer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Wires the module. It adds its rules through the {@link SecurityConfigurer} hook of
 * {@code authentication.jwt}, so it never defines a {@code SecurityFilterChain} of its own. A rule
 * that denies an anonymous caller yields 401 (the chain's entry point); a signed-in caller without
 * the permission gets 403.
 */
@AutoConfiguration(after = JwtAuthAutoConfiguration.class)
@EnableConfigurationProperties(RbacProperties.class)
public class RbacAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  PermissionPolicy mortarPermissionPolicy(RbacProperties properties) {
    return new PermissionPolicy(properties);
  }

  @Bean
  SecurityConfigurer mortarRbacSecurityConfigurer(PermissionPolicy policy) {
    return http ->
        http.authorizeHttpRequests(
            requests -> {
              for (RbacRules.Rule rule : RbacRules.of(policy)) {
                var url =
                    rule.method() == null
                        ? requests.requestMatchers(rule.patterns())
                        : requests.requestMatchers(rule.method(), rule.patterns());
                if (rule.roles().isEmpty()) {
                  url.denyAll();
                } else {
                  url.hasAnyRole(rule.roles().toArray(new String[0]));
                }
              }
            });
  }
}
