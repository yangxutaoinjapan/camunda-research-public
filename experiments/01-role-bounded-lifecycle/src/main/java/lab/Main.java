package lab;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.worker.JobWorker;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public final class Main {
    enum Window {
        MONTH("worker-role-monthly", 7), MINUTE("worker-role-minute", 16);
        final String process;
        final String keyExpression;
        /** Message names are global per engine: two definitions with the same message start would both open on one publish. */
        String message(String base) { return this == MONTH ? base : base + ".minute"; }
        Window(String process, int length) {
            this.process = process;
            this.keyExpression = "= \"role-\" + substring(string(now()), 1, " + length + ")";
        }
    }
    static String process(Window window) { return window.process; }
    static final String MINUTE_DEADLINE = "= date and time(substring(string(now()), 1, 16) + \":00Z\") + duration(\"PT1M\")";
    static String env(String key, String fallback) { return System.getenv().getOrDefault(key, fallback); }
    static long workMillis() { return Long.parseLong(env("WORK_MILLIS", "200")); }
    public static void main(String[] args) {
        int status = 0;
        try {
            if (args.length == 0) throw new IllegalArgumentException("Usage: deploy | worker | serve | publish --lane a|b|c [--count N] [--window month|minute] | experiment [--cleanup] | --cleanup");
            try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var client = client(executor)) {
                switch (args[0]) {
                    case "deploy" -> deploy(client, args.length == 3 && args[1].equals("--window")
                        ? Window.valueOf(args[2].toUpperCase(Locale.ROOT)) : Window.MONTH);
                    case "worker" -> {
                        try (var workers = new Workers(client)) {
                            Runtime.getRuntime().addShutdownHook(new Thread(workers::close));
                            new CountDownLatch(1).await();
                        }
                    }
                    case "serve" -> {
                        if (args.length != 1) throw new IllegalArgumentException("Usage: serve");
                        Serve.run(client);
                    }
                    case "publish" -> {
                        String lane = null; int count = 1; Window window = Window.MONTH;
                        for (int i = 1; i < args.length; i += 2) {
                            if (i + 1 == args.length) throw new IllegalArgumentException("Missing option value");
                            switch (args[i]) {
                                case "--lane" -> lane = args[i + 1];
                                case "--count" -> count = Integer.parseInt(args[i + 1]);
                                case "--window" -> window = Window.valueOf(args[i + 1].toUpperCase(Locale.ROOT));
                                default -> throw new IllegalArgumentException("Unknown option");
                            }
                        }
                        if (lane == null || !List.of("a", "b", "c").contains(lane) || count < 1) throw new IllegalArgumentException("Valid lane and positive count required");
                        publish(client, window, lane, count, workMillis());
                    }
                    case "experiment" -> {
                        if (args.length > 2 || (args.length == 2 && !args[1].equals("--cleanup")))
                            throw new IllegalArgumentException("Usage: experiment [--cleanup]");
                        new Experiment(client).run(); // Cleanup is mandatory, including on failure.
                    }
                    case "--cleanup", "cleanup" -> new Experiment(client).cleanup();
                    default -> throw new IllegalArgumentException("Unknown command");
                }
            }
        } catch (Exception e) {
            // Avoid dumping client configuration, request headers, or credential-bearing URIs.
            if (e instanceof AccessCredentials.ConfigurationException) System.err.println(e.getMessage());
            if (e instanceof IllegalStateException && e.getMessage() != null && e.getMessage().startsWith("Engine FEEL preflight")) System.err.println("Engine FEEL preflight failed; check expression support and UTC engine configuration");
            // The engine's own problem detail (status, title, validation text) names resources, never credentials.
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof io.camunda.client.api.command.ProblemException problem && problem.details() != null) {
                    var d = problem.details();
                    String detail = d.getDetail() == null ? "" : d.getDetail().replaceAll("\\s+", " ");
                    System.err.println("Engine problem: HTTP " + d.getStatus() + " " + d.getTitle() + ": "
                        + (detail.length() > 3000 ? detail.substring(0, 3000) + "..." : detail));
                    break;
                }
            }
            System.err.println("FAIL: " + e.getClass().getSimpleName() + ". Check configuration and engine diagnostics; no credentials are printed.");
            status = 1;
        }
        if (status != 0) System.exit(status);
    }
    private static CamundaClient client(ExecutorService executor) {
        AccessCredentials.requireRestEnvironment();
        var credentials = AccessCredentials.fromEnvironment();
        var b = CamundaClient.newClientBuilder().restAddress(URI.create(env("CAMUNDA_REST_ADDRESS", "http://localhost:8080")))
            .preferRestOverGrpc(true).defaultJobWorkerStreamEnabled(false).useClientSideLoadBalancing(false)
            .grpcAddress(URI.create("http://127.0.0.1:26500"))
            .credentialsProvider(credentials).applyEnvironmentVariableOverrides(true)
            .jobHandlingExecutor(executor, false).numJobWorkerExecutionThreads(1)
            .maxHttpConnections(8).defaultRequestTimeout(Duration.ofSeconds(15));
        return b.build();
    }
    static long deploy(CamundaClient c, Window window) throws Exception {
        String bpmn;
        try (var resource = Main.class.getResourceAsStream("/bpmn/worker-role-monthly.bpmn")) {
            if (resource == null) throw new IllegalStateException("Role BPMN resource missing");
            bpmn = new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        if (window == Window.MINUTE) {
            try {
                Object key = evaluate(c, window.keyExpression);
                Object deadline = evaluate(c, MINUTE_DEADLINE);
                if (!(key instanceof String text) || !text.matches("role-\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}"))
                    throw new IllegalStateException("Unexpected minute key result");
                if (deadline == null) throw new IllegalStateException("Minute deadline returned null");
                parseEngineTime(deadline.toString());
            } catch (RuntimeException e) {
                throw new IllegalStateException("Engine FEEL preflight failed for minute roleKey/deadline; check expression support and UTC engine configuration", e);
            }
            bpmn = bpmn.replace(Window.MONTH.process, window.process);
            for (String base : List.of("role.open", "role.close", "work.a", "work.b", "work.c"))
                bpmn = bpmn.replace("name=\"" + base + "\"", "name=\"" + window.message(base) + "\"");
        }
        var deployment = c.newDeployResourceCommand().addResourceStringUtf8(bpmn, window.process + ".bpmn");
        for (String lane : List.of("a", "b", "c"))
            deployment.addResourceFromClasspath("bpmn/lab-child-" + lane + ".bpmn");
        var response = deployment.send().join();
        System.out.println("Deployed " + process(window));
        return response.getProcesses().stream().filter(p -> p.getBpmnProcessId().equals(window.process))
            .findFirst().orElseThrow().getProcessDefinitionKey();
    }
    static Object evaluate(CamundaClient c, String expression) {
        var result = c.newEvaluateExpressionCommand().expression(expression).send().join();
        if (!result.getWarnings().isEmpty() || result.getResult() == null)
            throw new IllegalStateException("Engine FEEL evaluation failed: " + expression);
        return result.getResult();
    }
    static Instant parseEngineTime(String text) {
        int at = text.indexOf('@');
        return at > 0
            ? LocalDateTime.parse(text.substring(0, at)).atZone(ZoneId.of(text.substring(at + 1))).toInstant()
            : OffsetDateTime.parse(text).toInstant();
    }
    static String roleKey(CamundaClient c, Window window) {
        return evaluate(c, window.keyExpression).toString();
    }
    static String publish(CamundaClient c, Window window, String lane, int count, long millis) throws java.io.IOException {
        String last = new Schedule(c, window, null).openKey();
        for (int i = 0; i < count; i++) {
            c.newPublishMessageCommand().messageName(window.message("work." + lane)).correlationKey(last)
                .messageId(UUID.randomUUID().toString()).timeToLive(Duration.ZERO)
                .variables(Map.of("workMillis", millis)).send().join();
        }
        System.out.printf("Published %d work.%s; last route=%s (acceptance is not proof of correlation)%n", count, lane, last);
        return last;
    }
    static final class Workers implements AutoCloseable {
        private final List<JobWorker> workers = new ArrayList<>();
        Workers(CamundaClient c) {
            try {
                for (String lane : List.of("a", "b", "c")) workers.add(c.newWorker().jobType("lab.work." + lane)
                    .handler((jc, job) -> {
                        Object duration = job.getVariable("workMillis");
                        long millis = duration instanceof Number n ? n.longValue() : workMillis();
                        if (millis < 0 || millis > 600_000) throw new IllegalArgumentException("workMillis outside 0..600000");
                        try { Thread.sleep(millis); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                        jc.newCompleteCommand(job).send().join();
                    }).maxJobsActive(8).timeout(Duration.ofDays(90)).pollInterval(Duration.ofMillis(100)).open());
            } catch (RuntimeException e) { close(); throw e; }
        }
        public synchronized void close() { workers.forEach(JobWorker::close); workers.clear(); }
    }
}
