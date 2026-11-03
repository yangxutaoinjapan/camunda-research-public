package lab;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.enums.ProcessInstanceState;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Small same-JVM front end. Engine search remains the source of truth. */
final class Serve {
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    /** MONTH in production; LAB_WINDOW=minute lets you watch the generated minute variant locally. */
    private static final Main.Window WINDOW = Main.Window.valueOf(Main.env("LAB_WINDOW", "month").toUpperCase(java.util.Locale.ROOT));
    private final String basePath = normalizeBasePath(Main.env("LAB_BASE_PATH", ""));
    private final CamundaClient client;
    private final String token = System.getenv("LAB_HTTP_TOKEN");
    private byte[] cachedRoles;
    /** A finished role never changes again, so its row (with the counts of its child instances) is computed once. */
    private final Map<Long, Map<String, Object>> finishedRows = new HashMap<>();
    private long cachedAt;
    private final Schedule schedule;
    Serve(CamundaClient client) throws IOException {
        this.client = client;
        schedule = new Schedule(client, WINDOW, java.nio.file.Path.of(Main.env("STATE_DIRECTORY", "/tmp"), "schedule.json"));
    }

    static String normalizeBasePath(String path) {
        if (path.isEmpty()) return "";
        if (!path.matches("/[a-z0-9/_-]*"))
            throw new IllegalArgumentException("Invalid LAB_BASE_PATH: use a leading / and only a-z, 0-9, -, _, /");
        return path.replaceFirst("/+$", "");
    }

    static void run(CamundaClient client) throws Exception {
        var app = new Serve(client);
        var address = InetAddress.getByName(Main.env("LAB_HTTP_BIND", "127.0.0.1"));
        if (app.token != null && app.token.isBlank()) throw new IllegalArgumentException("Empty HTTP token");
        if (app.token == null && !address.isLoopbackAddress())
            throw new IllegalArgumentException("Non-loopback HTTP requires LAB_HTTP_TOKEN");
        int port = Integer.parseInt(Main.env("LAB_HTTP_PORT", "8088"));
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid HTTP port");
        String enabled = Main.env("LAB_SERVE_WORKERS", "true");
        if (!List.of("true", "false").contains(enabled)) throw new IllegalArgumentException("Invalid worker setting");
        byte[] page;
        try (var in = Serve.class.getResourceAsStream("/web/index.html")) {
            if (in == null) throw new IllegalStateException("Web page missing");
            page = in.readAllBytes();
        }
        var server = HttpServer.create(new InetSocketAddress(address, port), 32);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var workers = enabled.equals("true") ? new Main.Workers(client) : null;
             var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            server.setExecutor(executor);
            server.createContext("/", x -> app.handle(x, page));
            var stopped = new CountDownLatch(1);
            var hook = new Thread(() -> { server.stop(1); stopped.countDown(); });
            Runtime.getRuntime().addShutdownHook(hook);
            try {
                scheduler.scheduleWithFixedDelay(() -> {
                    try { app.schedule.tick(Instant.now()); }
                    catch (RuntimeException e) { System.err.println("Scheduler request failed; retrying in 15 seconds"); }
                }, 0, 15, TimeUnit.SECONDS);
                server.start();
                System.out.println("HTTP front end started; workers=" + enabled);
                stopped.await();
            } finally {
                scheduler.shutdownNow();
                server.stop(1);
                try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException ignored) { }
            }
        } finally { server.stop(0); }
    }

    private void handle(HttpExchange x, byte[] page) throws IOException {
        try (x) {
            x.getResponseHeaders().set("Cache-Control", "no-store");
            x.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            x.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
            String path = x.getRequestURI().getRawPath();
            if (!basePath.isEmpty() && path.equals(basePath)) {
                x.getResponseHeaders().set("Location", basePath + "/");
                x.sendResponseHeaders(308, -1); return;
            }
            if (!path.startsWith(basePath + "/")) {
                reply(x, 404, Map.of("error", "Not found")); return;
            }
            path = path.substring(basePath.length());
            if (path.equals("/api") || path.startsWith("/api/")) {
                String supplied = x.getRequestHeaders().getFirst("X-Lab-Token");
                if (token != null && (supplied == null || !MessageDigest.isEqual(
                        token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))) {
                    reply(x, 401, Map.of("error", "Authentication required")); return;
                }
            }
            try {
                switch (path) {
                    case "/" -> {
                        if (!method(x, "GET")) return;
                        x.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                        x.sendResponseHeaders(200, page.length); x.getResponseBody().write(page);
                    }
                    case "/api/roles" -> { if (method(x, "GET")) send(x, 200, roles()); }
                    case "/api/clock" -> {
                        if (!method(x, "GET")) return;
                        reply(x, 200, Map.of("now", Main.parseEngineTime(Main.evaluate(client, "= string(now())").toString()).toString()));
                    }
                    case "/api/schedule" -> {
                        if (x.getRequestMethod().equals("POST")) schedule.save(Schedule.parse(body(x)));
                        else if (!method(x, "GET")) return;
                        reply(x, 200, schedule.status(Instant.now()));
                    }
                    case "/api/open" -> {
                        if (!method(x, "POST")) return;
                        JsonNode data = body(x);
                        if (data == null || !data.isObject() || data.size() != 2 || !data.has("name") || !data.get("name").isTextual()
                            || !data.has("closeMinutes") || !integer(data.get("closeMinutes"), 1, 525600))
                            throw new IllegalArgumentException("Expected name and closeMinutes (1..525600); no other fields");
                        reply(x, 200, Map.of("roleKey", schedule.openNamed(data.get("name").textValue(), data.get("closeMinutes").longValue(), Instant.now())));
                    }
                    case "/api/publish" -> {
                        if (!method(x, "POST")) return;
                        JsonNode data = body(x);
                        if (data == null || !data.isObject() || data.size() < 3 || data.size() > 4 || !data.has("lane")
                            || !data.has("count") || !data.has("workMillis") || !data.get("lane").isTextual()
                            || !List.of("a", "b", "c").contains(data.get("lane").textValue())
                            || !integer(data.get("count"), 1, 20) || !integer(data.get("workMillis"), 0, 600000)
                            || (data.size() == 4 && !(data.has("roleKey") && data.get("roleKey").isTextual() && data.get("roleKey").textValue().length() <= 120)))
                            throw new IllegalArgumentException("Expected lane a|b|c, count 1..20, workMillis 0..600000 and an optional roleKey; no other fields");
                        var open = schedule.openKeys();
                        String key = data.has("roleKey") ? data.get("roleKey").textValue() : open.isEmpty() ? null : open.getFirst();
                        if (key == null) throw new IllegalArgumentException("No open role. Open one first.");
                        if (!open.contains(key)) throw new IllegalArgumentException("That role is not open any more: it is draining, closed or unknown.");
                        for (int i=0; i<data.get("count").intValue(); i++) client.newPublishMessageCommand()
                            .messageName(WINDOW.message("work."+data.get("lane").textValue())).correlationKey(key)
                            .messageId(UUID.randomUUID().toString()).timeToLive(Duration.ZERO)
                            .variables(Map.of("workMillis",data.get("workMillis").longValue())).send().join();
                        reply(x, 200, Map.of("roleKey", key));
                    }
                    case "/api/cancel" -> {
                        if (!method(x, "POST")) return;
                        JsonNode data = body(x);
                        if (data == null || !data.isObject() || data.size() != 1 || !data.has("key") || !data.get("key").isTextual()
                            || !data.get("key").textValue().matches("[0-9]{1,19}"))
                            throw new IllegalArgumentException("Expected the role's process instance key");
                        long id = Long.parseLong(data.get("key").textValue());
                        var found = client.newProcessInstanceSearchRequest().filter(f -> f.processInstanceKey(id)
                            .processDefinitionId(Main.process(WINDOW)).state(ProcessInstanceState.ACTIVE)).send().join().items();
                        if (found.isEmpty()) throw new IllegalArgumentException("That role is not running any more.");
                        var named = client.newVariableSearchRequest().filter(f -> f.processInstanceKey(id).scopeKey(id).name("roleKey")).send().join().items();
                        client.newCancelInstanceCommand(id).send().join();
                        synchronized (this) { cachedRoles = null; }
                        String cancelled = named.isEmpty() ? data.get("key").textValue() : named.getFirst().getValueAsType(String.class);
                        // Cancelling the role of the schedule's CURRENT period must also switch the schedule off: the scheduler opens the
                        // current period's role whenever none is open, so a cancelled role came back within seconds (2026-11-03).
                        // The rules stay, so the schedule can be switched on again; a manually opened role does not touch the schedule.
                        var status = schedule.status(Instant.now());
                        var config = (Schedule.Config) status.get("config");
                        boolean scheduleOff = false;
                        if (config.enabled() && cancelled.equals(status.get("currentRoleKey"))) {
                            schedule.save(new Schedule.Config(false, config.start(), config.close()));
                            scheduleOff = true;
                        }
                        reply(x, 200, Map.of("roleKey", cancelled, "scheduleOff", scheduleOff));
                    }
                    default -> reply(x, 404, Map.of("error", "Not found"));
                }
            } catch (IllegalArgumentException e) {
                reply(x, 400, Map.of("error", e.getMessage() == null ? "Invalid request" : e.getMessage()));
            } catch (Exception e) {
                System.err.println("api request failed: " + x.getRequestURI() + ": " + e);
                reply(x, 502, Map.of("error", "Request failed; publication may be partial. Check service and engine diagnostics before retrying."));
            }
        }
    }
    private static JsonNode body(HttpExchange x) throws IOException {
        String type=x.getRequestHeaders().getFirst("Content-Type");
        if(type==null || !type.split(";",2)[0].trim().equalsIgnoreCase("application/json"))
            throw new IllegalArgumentException("Use application/json");
        byte[] bytes=x.getRequestBody().readNBytes(4097);
        if(bytes.length>4096)throw new IllegalArgumentException("Body too large");
        try { return JSON.readTree(bytes); }
        catch(IOException e){throw new IllegalArgumentException("Invalid JSON");}
    }
    private static boolean integer(JsonNode n, int min, int max) {
        return n.isIntegralNumber() && n.canConvertToInt() && n.intValue() >= min && n.intValue() <= max;
    }
    private static boolean method(HttpExchange x, String expected) throws IOException {
        if (x.getRequestMethod().equals(expected)) return true;
        x.getResponseHeaders().set("Allow", expected);
        reply(x, 405, Map.of("error", "Method not allowed")); return false;
    }
    private static void reply(HttpExchange x, int code, Object data) throws IOException { send(x, code, JSON.writeValueAsBytes(data)); }
    private static void send(HttpExchange x, int code, byte[] data) throws IOException {
        x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        x.sendResponseHeaders(code, data.length); x.getResponseBody().write(data);
    }
    private synchronized byte[] roles() throws IOException {
        if (cachedRoles != null && System.nanoTime() - cachedAt < TimeUnit.SECONDS.toNanos(3)) return cachedRoles;
        var result = new ArrayList<Map<String, Object>>();
        var roles = Instances.search(client, f -> f.processDefinitionId(Main.process(WINDOW)));
        roles.sort(Comparator.comparing((io.camunda.client.api.search.response.ProcessInstance p) -> p.getState() != ProcessInstanceState.ACTIVE)
            .thenComparing(io.camunda.client.api.search.response.ProcessInstance::getStartDate, Comparator.reverseOrder()));
        // Only the newest dozen are shown; the history of a long-running lab can hold hundreds, and each role costs several searches.
        if (roles.size() > 12) roles = new ArrayList<>(roles.subList(0, 12));
        var openNow = new HashSet<>(schedule.openKeys());
        for (var role : roles) {
            long key = role.getProcessInstanceKey();
            if (role.getState() != ProcessInstanceState.ACTIVE && finishedRows.containsKey(key)) { result.add(finishedRows.get(key)); continue; }
            var row = new LinkedHashMap<String, Object>();
            row.put("key", Long.toString(key));
            var variables = client.newVariableSearchRequest().filter(f -> f.processInstanceKey(key).scopeKey(key).name("roleKey"))
                .send().join().items();
            row.put("roleKey", variables.isEmpty() ? null : variables.getFirst().getValueAsType(String.class));
            var deadlines = client.newVariableSearchRequest().filter(f -> f.processInstanceKey(key).scopeKey(key).name("deadline")).send().join().items();
            row.put("closes", deadlines.isEmpty() ? null : deadlines.getFirst().getValueAsType(String.class));
            row.put("state", role.getState().toString());
            row.put("start", role.getStartDate() == null ? null : role.getStartDate().toString());
            row.put("end", role.getEndDate() == null ? null : role.getEndDate().toString());
            row.put("incident", Boolean.TRUE.equals(role.getHasIncident()));
            var lanes = new LinkedHashMap<String, Object>();
            for (String lane : List.of("a", "b", "c")) {
                var counts = new LinkedHashMap<String, Long>();
                for (var state : List.of(ProcessInstanceState.ACTIVE, ProcessInstanceState.COMPLETED)) {
                    var search = client.newProcessInstanceSearchRequest()
                        .filter(f -> f.processDefinitionId("lab-child-" + lane).parentProcessInstanceKey(key).state(state))
                        .page(p -> p.limit(1)).send().join();
                    if (search.page().totalItems() == null || Boolean.TRUE.equals(search.page().hasMoreTotalItems()))
                        throw new IllegalStateException("Exact child count unavailable");
                    counts.put(state.toString(), search.page().totalItems());
                }
                lanes.put(lane, counts);
            }
            row.put("lanes", lanes);
            row.put("open", role.getState() == ProcessInstanceState.ACTIVE && row.get("roleKey") != null && openNow.contains(row.get("roleKey")));
            if (role.getState() == ProcessInstanceState.ACTIVE) {
                var running = new LinkedHashMap<String, Object>();
                for (String lane : List.of("a", "b", "c")) {
                    var items = new ArrayList<Map<String, Object>>();
                    for (var child : Instances.children(client, lane, key, ProcessInstanceState.ACTIVE)) {
                        if (items.size() >= 30) break;
                        items.add(Map.of("key", Long.toString(child.getProcessInstanceKey()), "start", child.getStartDate() == null ? "" : child.getStartDate().toString()));
                    }
                    running.put(lane, items);
                }
                row.put("running", running);
            }
            // a cancelled role's children can still show as running in the search index for a few seconds: cache the row only once none does
            if (role.getState() != ProcessInstanceState.ACTIVE && lanes.values().stream().allMatch(l -> ((Map<?, ?>) l).get("ACTIVE").equals(0L))) finishedRows.put(key, row);
            result.add(row);
        }
        cachedRoles = JSON.writeValueAsBytes(Map.of("roles", result)); cachedAt = System.nanoTime(); return cachedRoles;
    }
}
