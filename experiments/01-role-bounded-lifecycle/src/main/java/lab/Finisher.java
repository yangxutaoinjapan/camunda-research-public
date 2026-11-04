package lab;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.enums.ProcessInstanceState;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Finishes child instances by message. A child waits at one message catch event (work.a|b|c.done, correlation key childId); nothing
 * claims it and no worker is involved. Java publishes the finish message once the child's work time has passed.
 *
 * Two paths, because a delayed publish that lives only in memory is lost with the process:
 * the fast path is a delayed publish scheduled when the work event is sent; the backstop is {@link #reconcile()}, which reads the
 * engine's ACTIVE children and publishes the finish for every one whose start time plus workMillis has passed. The backstop is what
 * makes a restart of this JVM harmless.
 */
final class Finisher implements AutoCloseable {
    /** The finish message is buffered this long, so it still reaches a child whose catch event is not open yet. */
    static final Duration TTL = Duration.ofMinutes(10);
    private record Known(String childId, long workMillis) {}
    private final CamundaClient client;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Map<Long, Known> known = new HashMap<>();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    Finisher(CamundaClient client) { this.client = client; }

    /** Publishes the finish message for one child. A repeat within the TTL is rejected as ALREADY_EXISTS; that means it was sent. */
    static void publishDone(CamundaClient client, String lane, String childId) {
        try {
            client.newPublishMessageCommand().messageName("work." + lane + ".done").correlationKey(childId).messageId(childId)
                .timeToLive(TTL).send().join();
        } catch (io.camunda.client.api.command.ProblemException e) {
            if (e.details() == null || e.details().getStatus() == null || e.details().getStatus() != 409) throw e;
        }
    }

    /** Fast path: finish the child after its work time. A failure is left to the next {@link #reconcile()}. */
    void track(String lane, String childId, long workMillis) {
        pending.add(childId);
        scheduler.schedule(() -> {
            try { publishDone(client, lane, childId); }
            catch (RuntimeException e) { System.err.println("Finish message failed; the reconciler retries: " + e.getClass().getSimpleName()); }
            finally { pending.remove(childId); }
        }, workMillis, TimeUnit.MILLISECONDS);
    }

    /** Runs reconcile every few seconds until closed. */
    void start(Duration every) {
        scheduler.scheduleWithFixedDelay(() -> {
            try { reconcile(); }
            catch (RuntimeException e) { System.err.println("Reconcile failed; retrying: " + e.getClass().getSimpleName()); }
        }, 0, every.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Backstop: publishes the finish message for every ACTIVE child whose work time is over. Returns how many it sent. */
    synchronized int reconcile() {
        int sent = 0;
        var present = new HashSet<Long>();
        for (String lane : List.of("a", "b", "c")) {
            for (var child : Instances.search(client, f -> f.processDefinitionId("lab-child-" + lane).state(ProcessInstanceState.ACTIVE))) {
                long key = child.getProcessInstanceKey();
                present.add(key);
                var info = known.get(key);
                if (info == null) {
                    String id = null; long millis = -1;
                    for (var v : client.newVariableSearchRequest().filter(f -> f.processInstanceKey(key).scopeKey(key)).send().join().items()) {
                        if (v.getName().equals("childId")) id = v.getValueAsType(String.class);
                        if (v.getName().equals("workMillis")) millis = Long.parseLong(v.getValue());
                    }
                    if (id == null || millis < 0) continue; // variables not exported yet; the next round reads them
                    info = new Known(id, millis); known.put(key, info);
                }
                if (child.getStartDate() == null || pending.contains(info.childId())) continue;
                if (!child.getStartDate().toInstant().plusMillis(info.workMillis()).isAfter(Instant.now())) {
                    publishDone(client, lane, info.childId()); sent++;
                }
            }
        }
        known.keySet().retainAll(present);
        return sent;
    }

    /** Blocks until every child tracked by {@link #track} has had its finish message published. */
    void awaitTracked(Duration limit) throws InterruptedException {
        long end = System.nanoTime() + limit.toNanos();
        while (!pending.isEmpty() && System.nanoTime() < end) Thread.sleep(100);
    }

    @Override public void close() { scheduler.shutdownNow(); }
}
