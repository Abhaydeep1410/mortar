package dev.reklo.oracle.harness;

import java.time.Duration;
import org.junit.jupiter.api.BeforeAll;

/** Base of every per-prompt API suite: waits for the stack, then the suite holds only criteria. */
public abstract class OracleBase {
    @BeforeAll
    static void appIsUp() {
        Api.awaitHealthy(Duration.ofSeconds(Long.getLong("reklo.healthTimeoutS", 60L)));
    }
}
