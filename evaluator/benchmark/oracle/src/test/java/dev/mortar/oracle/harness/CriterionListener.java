package dev.mortar.oracle.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestTag;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * Turns tagged test results into the run record's `oracle` object:
 * {"passed": n, "total": m, "by_criterion": {"crm-01.1": true, ...}}.
 *
 * A criterion is every test tagged with its id (for example `crm-01.5`); it passes only if all of them
 * pass. A test that is skipped, aborted, or never ran (for example because the class setup failed) counts
 * as a failure, so a dead stack scores 0, not "no data". Registered through ServiceLoader. Output path:
 * -Dmortar.oracleResults (default target/oracle-results.json); nothing is written when no criterion tests ran.
 */
public final class CriterionListener implements TestExecutionListener {
    private static final Pattern CRITERION = Pattern.compile("[a-z]+-\\d+\\.\\d+");

    private final Map<String, int[]> counts = new TreeMap<>(); // id -> {passed, total}
    private final Map<String, List<String>> byTest = new LinkedHashMap<>(); // unique id -> criteria

    @Override
    public void testPlanExecutionStarted(TestPlan plan) {
        for (TestIdentifier root : plan.getRoots()) {
            for (TestIdentifier t : plan.getDescendants(root)) {
                if (!t.isTest()) continue;
                List<String> ids = t.getTags().stream().map(TestTag::getName).filter(n -> CRITERION.matcher(n).matches()).toList();
                if (ids.isEmpty()) continue;
                byTest.put(t.getUniqueId(), ids);
                for (String id : ids) counts.computeIfAbsent(id, k -> new int[2])[1]++;
            }
        }
    }

    @Override
    public void executionFinished(TestIdentifier t, TestExecutionResult result) {
        List<String> ids = byTest.get(t.getUniqueId());
        if (ids == null || result.getStatus() != TestExecutionResult.Status.SUCCESSFUL) return;
        for (String id : ids) counts.get(id)[0]++;
    }

    @Override
    public void testPlanExecutionFinished(TestPlan plan) {
        if (counts.isEmpty()) return;
        Map<String, Object> byCriterion = new LinkedHashMap<>();
        int passed = 0;
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            boolean ok = e.getValue()[0] == e.getValue()[1];
            byCriterion.put(e.getKey(), ok);
            if (ok) passed++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("passed", (long) passed);
        out.put("total", (long) counts.size());
        out.put("by_criterion", byCriterion);
        Path path = Path.of(System.getProperty("mortar.oracleResults", "target/oracle-results.json"));
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Files.writeString(path, Json.write(out) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write " + path, e);
        }
    }
}
