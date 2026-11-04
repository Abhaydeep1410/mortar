package com.mortar.crud;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Wires the module's properties and error handler. Controllers are written by the application. */
@AutoConfiguration
@EnableConfigurationProperties(CrudProperties.class)
public class CrudAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  CrudExceptionHandler mortarCrudExceptionHandler() {
    return new CrudExceptionHandler();
  }
}
