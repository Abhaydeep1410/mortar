package com.mortar.crud;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code mortar.crud}. Invalid values fail application startup.
 *
 * @param defaultPageSize page size when the request has no {@code size}
 * @param maxPageSize largest accepted {@code size}; larger values are rejected with 400
 */
@ConfigurationProperties(prefix = "mortar.crud")
public record CrudProperties(
    @DefaultValue("20") int defaultPageSize, @DefaultValue("100") int maxPageSize) {

  public CrudProperties {
    if (defaultPageSize < 1) {
      throw new IllegalArgumentException("mortar.crud.default-page-size must be at least 1");
    }
    if (maxPageSize < defaultPageSize || maxPageSize > 1000) {
      throw new IllegalArgumentException(
          "mortar.crud.max-page-size must be between default-page-size and 1000");
    }
  }
}
