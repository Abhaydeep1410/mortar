package com.mortar.csv;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code mortar.export.csv}. Invalid values fail application startup.
 *
 * @param batchSize rows read from the database per round trip, 1 to 5000
 * @param neutralizeFormulas when true, a cell starting with {@code = + - @}, a tab or a carriage
 *     return gets a leading apostrophe so spreadsheets do not run it as a formula
 */
@ConfigurationProperties(prefix = "mortar.export.csv")
public record CsvExportProperties(
    @DefaultValue("500") int batchSize, @DefaultValue("true") boolean neutralizeFormulas) {

  public CsvExportProperties {
    if (batchSize < 1 || batchSize > 5000) {
      throw new IllegalArgumentException("mortar.export.csv.batch-size must be between 1 and 5000");
    }
  }
}
