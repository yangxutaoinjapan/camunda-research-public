package lab;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.response.ProcessInstance;
import io.camunda.client.api.search.enums.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** All behavioral assertions use REST search, never worker-side completion counters. */
final class Experiment {
    private final CamundaClient c;
    private final List<String[]> checks = new ArrayList<>();
    private final long waitSeconds = Long.parseLong(Main.env("POLL_TIMEOUT_SECONDS", "150"));
    Experiment(CamundaClient c) { this.c = c; }
    private final Main.Window window = Main.Window.MINUTE;
    private Long definitionKey;
    private Schedule scheduler;
    private boolean ticking;
    private Instant begin;
    private java.util.Map<String, Long> baseline = java.util.Map.of("ROOT", 0L, "CHILD", 0L);
    void run() throws Exception {
        System.out.println("Fresh exclusive disposable engine required; real time, engine timezone UTC.");
        try (var workers = new Main.Workers(c)) {
            // History of earlier runs stays in the search index; only ACTIVE lab instances and a deployed minute definition
            // disturb the proof. Everything below counts what started after `begin` and compares totals with the baseline.
            // A deleted definition can still be listed by the search index, so only ACTIVE instances count as "not clean".
            check("clean engine: no active lab instances", () -> activeRoles().isEmpty()
                && Instances.search(c, f -> f.state(ProcessInstanceState.ACTIVE).processDefinitionId("lab-child-a")).isEmpty()
                && Instances.search(c, f -> f.state(ProcessInstanceState.ACTIVE).processDefinitionId("lab-child-b")).isEmpty()
                && Instances.search(c, f -> f.state(ProcessInstanceState.ACTIVE).processDefinitionId("lab-child-c")).isEmpty());
            baseline = Instances.summary(c);
            begin = Instant.now().minusSeconds(2);
            System.out.println("baseline before this run: ROOT " + baseline.get("ROOT") + ", CHILD " + baseline.get("CHILD") + " (history)");
            definitionKey = Main.deploy(c, window);
            scheduler = new Schedule(c, window, null);
            scheduler.save(new Schedule.Config(true, "every 1 minute", "after 1 minute"));
            // Start near the beginning of a fresh period, leaving time for setup.
            while (Instant.now().getEpochSecond() % 60 > 2) Thread.sleep(250);
            ticking = true;
            check("first scheduled role W1 appears", () -> roles().size() == 1 && active(roles().getFirst()));
            long w1 = roles().getFirst().getProcessInstanceKey();
            String key1 = startKey(role(w1));
            Instant deadline = role(w1).getStartDate().toInstant().truncatedTo(java.time.temporal.ChronoUnit.MINUTES).plusSeconds(60);
            check("W1 key matches engine start minute; three active branches", () -> hasRole(w1, key1) && branchesActive(w1));
            check("W1 deadline equals next minute", () -> c.newVariableSearchRequest()
                .filter(f -> f.processInstanceKey(w1).scopeKey(w1).name("deadline")).send().join().items().stream()
                .anyMatch(v -> deadline.equals(Main.parseEngineTime(v.getValueAsType(String.class)))));
            for (String lane : lanes()) Main.publish(c, window, lane, 3, 200);
            check("W1 three completed child instances per lane; still active", () -> events(w1, 3, ProcessInstanceState.COMPLETED) && active(role(w1)));
            for (String lane : lanes()) Main.publish(c, window, lane, 1, 70_000);
            check("W1 slow child instance active in every lane", () -> events(w1, 1, ProcessInstanceState.ACTIVE));
            check("W2 starts while W1 drains", () -> next(w1) != null && active(next(w1)) && active(role(w1)));
            long w2 = next(w1).getProcessInstanceKey();
            String key2 = startKey(role(w2));
            check("W2 different roleKey and three active branches", () -> !key1.equals(key2) && hasRole(w2, key2) && branchesActive(w2));
            check("W1 deadline timers completed while slow children and role remain active", () -> {
                for (String lane : lanes()) if (count(w1, "deadline_" + lane, ElementInstanceState.COMPLETED) != 1) return false;
                return events(w1, 1, ProcessInstanceState.ACTIVE) && active(role(w1));
            });
            for (String lane : lanes()) Main.publish(c, window, lane, 2, 200);
            check("W2 has two completed child instances per lane; W1 stays at four", () -> events(w2, 2, ProcessInstanceState.COMPLETED) && events(w1, 4, null));
            check("W1 completed after slow drain; W2 still active", () -> role(w1).getState() == ProcessInstanceState.COMPLETED && active(role(w2)));
            check("W1 four completed child instances per lane and end after deadline", () -> events(w1, 4, ProcessInstanceState.COMPLETED)
                && role(w1).getEndDate() != null && role(w1).getEndDate().toInstant().isAfter(deadline));
            check("exactly 2 ROOT roles and 18 CHILD instances with correct ancestry", () -> {
                var summary = Instances.summary(c);
                return summary.get("ROOT") - baseline.get("ROOT") == 2L && summary.get("CHILD") - baseline.get("CHILD") == 18L
                    && roles().size() == 2 && roles().stream().allMatch(p -> !Instances.hasParent(p))
                    && events(w1, 4, ProcessInstanceState.COMPLETED)
                    && events(w2, 2, ProcessInstanceState.COMPLETED);
            });
            ticking = false;
            // Separate early-close phase after the 18-child deadline proof.
            for (String lane : lanes()) Main.publish(c, window, lane, 1, 20_000);
            check("early close: W2 child active in every lane", () -> events(w2, 1, ProcessInstanceState.ACTIVE));
            scheduler.closeNow();
            check("early close: all three message catches complete while W2 drains", () -> {
                for (String lane : lanes()) if (count(w2, "close_" + lane, ElementInstanceState.COMPLETED) != 1) return false;
                return active(role(w2)) && events(w2, 1, ProcessInstanceState.ACTIVE);
            });
            check("early close: W2 completes after all children drain", () ->
                role(w2).getState() == ProcessInstanceState.COMPLETED && events(w2, 3, ProcessInstanceState.COMPLETED));
            printTable();
            System.out.println("Instances (lab definitions): " + Instances.summary(c));
            System.out.println("Deadline phase: E=18; early-close phase adds 3, final total 2 roots and 21 children.");
            System.out.println("a naive message-start-per-event design would have produced 18 root instances");
            var first = role(w1);
            System.out.printf("W1 lifetime = window %s + drain %s = %s (single sample)%n",
                Duration.between(first.getStartDate().toInstant(), deadline),
                Duration.between(deadline, first.getEndDate().toInstant()),
                Duration.between(first.getStartDate(), first.getEndDate()));
        } catch (Exception e) {
            checks.add(new String[]{"FAIL", "experiment aborted: " + e.getClass().getSimpleName()});
            throw e;
        } finally {
            ticking = false;
            try { cleanup(); }
            finally {
                System.out.println("\nRESULT | ASSERTION");
                checks.forEach(r -> System.out.println(r[0] + " | " + r[1]));
                System.out.println("Stop the disposable engine after collecting results (c8run stop). If deletion failed, its minute message-start definition is still deployed.");
            }
        }
    }
    private String startKey(ProcessInstance p) {
        return "role-" + p.getStartDate().withOffsetSameInstant(ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd\'T\'HH:mm"));
    }
    private boolean events(long key, long expected, ProcessInstanceState state) {
        for (String lane : lanes()) {
            var children = Instances.children(c, lane, key, state);
            if (children.size() != expected || children.stream().anyMatch(p ->
                !Objects.equals(p.getParentProcessInstanceKey(), key)
                || !Objects.equals(p.getRootProcessInstanceKey(), key))) return false;
        }
        return true;
    }
    private void printTable() {
        System.out.println("\nROLE | KEY | START (engine) | END (engine) | CHILD INSTANCES | LIFETIME");
        for (var p : roles()) {
            long total = 0;
            for (String lane : lanes()) total += Instances.children(c, lane, p.getProcessInstanceKey(), null).size();
            System.out.printf("%s | %d | %s | %s | %d | %s%n", startKey(p), p.getProcessInstanceKey(),
                p.getStartDate(), p.getEndDate(), total,
                p.getEndDate() == null ? "still active" : Duration.between(p.getStartDate(), p.getEndDate()));
        }
    }
    void cleanup() throws Exception {
        System.out.println("--cleanup: cancel minute roles and delete minute definitions");
        try {
            // Include the deployment response key: search may not have exported it yet on failure.
            Set<Long> definitions = new HashSet<>();
            if (definitionKey != null) definitions.add(definitionKey);
            var found = c.newProcessDefinitionSearchRequest().filter(f -> f.processDefinitionId(Main.process(window)))
                .page(p -> p.limit(100)).send().join().items();
            if (found.size() >= 100) throw new IllegalStateException("Definition search bound exceeded");
            found.forEach(d -> definitions.add(d.getProcessDefinitionKey()));
            // Stop the schedule first: deleting the definition stops the every-minute starts. If the engine refuses while
            // instances still run, cancel them and delete again. Cancels tolerate NOT_FOUND: a minute role lives about a minute
            // and may have completed between the (eventually consistent) search and the cancel.
            boolean deleted = true;
            try {
                for (long key : definitions) deleteQuietly(key);
            } catch (io.camunda.client.api.command.ProblemException refused) {
                deleted = false;
            }
            for (int round = 0; round < 20; round++) {
                var open = activeRoles();
                if (open.isEmpty()) break;
                for (var p : open) cancelQuietly(p.getProcessInstanceKey());
            }
            if (!deleted) for (long key : definitions) deleteQuietly(key);
            check("cleanup: no active minute roles or their children", () -> {
                var open = activeRoles();
                for (var p : open) cancelQuietly(p.getProcessInstanceKey());
                if (!open.isEmpty()) return false;
                for (String lane : lanes()) {
                    var kids = c.newProcessInstanceSearchRequest().filter(f -> f.processDefinitionId("lab-child-" + lane).state(ProcessInstanceState.ACTIVE))
                        .page(pg -> pg.limit(100)).send().join().items();
                    if (!kids.isEmpty()) return false;
                }
                return true;
            });
            checks.add(new String[]{"PASS", "cleanup: minute definitions deleted"});
        } catch (Exception e) {
            System.err.println("Cleanup failed: stop the disposable engine (c8run stop); minute message-start definition may still be deployed.");
            checks.add(new String[]{"FAIL", "cleanup failed; stop disposable engine to stop scheduler"});
            throw e;
        }
    }
    private List<String> lanes() { return List.of("a", "b", "c"); }
    private void deleteQuietly(long key) {
        try {
            c.newDeleteResourceCommand(key).send().join();
        } catch (io.camunda.client.api.command.ProblemException e) {
            if (e.details() == null || e.details().getStatus() == null || e.details().getStatus() != 404) throw e;
        }
    }
    private void cancelQuietly(long key) {
        try {
            c.newCancelInstanceCommand(key).send().join();
        } catch (io.camunda.client.api.command.ProblemException e) {
            if (e.details() == null || e.details().getStatus() == null || e.details().getStatus() != 404) throw e;
        }
    }
    private List<ProcessInstance> activeRoles() {
        return c.newProcessInstanceSearchRequest().filter(f -> f.processDefinitionId(Main.process(window)).state(ProcessInstanceState.ACTIVE))
            .page(p -> p.limit(100)).send().join().items();
    }
    private List<ProcessInstance> roles() {
        var all = Instances.search(c, f -> f.processDefinitionId(Main.process(window)));
        if (begin == null) return all;
        return all.stream().filter(p -> p.getStartDate() != null && !p.getStartDate().toInstant().isBefore(begin)).toList();
    }
    private ProcessInstance role(long key) {
        return c.newProcessInstanceSearchRequest().filter(f -> f.processInstanceKey(key)).send().join().singleItem();
    }
    private ProcessInstance next(long dec) { return roles().stream().filter(p -> p.getProcessInstanceKey() != dec)
        .min(Comparator.comparing(ProcessInstance::getStartDate)).orElse(null); }
    private boolean active(ProcessInstance p) { return p != null && p.getState() == ProcessInstanceState.ACTIVE; }
    private boolean hasRole(long key, String value) {
        return c.newVariableSearchRequest().filter(f -> f.processInstanceKey(key).scopeKey(key).name("roleKey"))
            .send().join().items().stream().anyMatch(v -> value.equals(v.getValueAsType(String.class)));
    }
    private boolean branchesActive(long key) {
        for (String lane : lanes()) if (count(key, "branch_" + lane, ElementInstanceState.ACTIVE) != 1) return false;
        return true;
    }
    private long count(long key, String id, ElementInstanceState state) {
        var result = c.newElementInstanceSearchRequest().filter(f -> {
            f.processInstanceKey(key).elementId(id);
            if (state != null) f.state(state);
        }).page(p -> p.limit(100)).send().join().items();
        if (result.size() >= 100) throw new IllegalStateException("Element count exceeds experiment bound");
        return result.size();
    }
    private void check(String label, BooleanSupplier assertion) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(waitSeconds).toNanos();
        RuntimeException last = null;
        do {
            try { if (ticking) scheduler.tick(Instant.now()); if (assertion.getAsBoolean()) { checks.add(new String[]{"PASS", label}); System.out.println("PASS | " + label); return; } }
            catch (RuntimeException e) { last = e; }
            Thread.sleep(250);
        } while (System.nanoTime() < until);
        checks.add(new String[]{"FAIL", label + (last == null ? " (timeout)" : " (" + last.getClass().getSimpleName() + ": " + String.valueOf(last.getMessage()).replaceAll("\\s+", " ") + ")")});
        throw new IllegalStateException("Assertion failed: " + label);
    }
}
