package com.mortar.csv;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Registers the module's properties. Export controllers are written by the application. */
@AutoConfiguration
@EnableConfigurationProperties(CsvExportProperties.class)
public class CsvExportAutoConfiguration {}
