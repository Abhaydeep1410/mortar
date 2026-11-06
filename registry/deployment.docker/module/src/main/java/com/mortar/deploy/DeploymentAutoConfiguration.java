package com.mortar.deploy;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.PropertySource;

/**
 * Adds the module's default settings (health endpoint only, no health details, Hibernate creates
 * the application's own tables, Flyway runs the baseline) at the lowest precedence, so the
 * application's own configuration and environment variables always win.
 */
@AutoConfiguration
@PropertySource("classpath:mortar-deployment.properties")
public class DeploymentAutoConfiguration {}
