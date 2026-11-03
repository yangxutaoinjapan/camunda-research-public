package lab;

import com.fasterxml.jackson.databind.*;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.enums.ProcessInstanceState;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

/** UTC calendar arithmetic shared by serve and the live minute proof. */
final class Schedule {
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    record Config(boolean enabled, String start, String close) {}
    record Period(String key, Instant start, Instant next, Instant close) {}
    static final Config DEFAULT = new Config(false, "day of month 1 at 00:00", "at day of month 1 00:00 of the next period");
    static final Pattern MONTH = Pattern.compile("day of month ([1-9]|1[0-9]|2[0-8]) at ([01][0-9]|2[0-3]):([0-5][0-9])");
    static final Pattern MINUTES = Pattern.compile("every ([1-9][0-9]{0,3}) minutes?");
    static final Pattern AFTER = Pattern.compile("after ([1-9][0-9]{0,5}) (minutes?|hours?|days?)");
    static final Pattern NEXT = Pattern.compile("at day of month ([1-9]|1[0-9]|2[0-8]) ([01][0-9]|2[0-3]):([0-5][0-9]) of the next period");
    private final CamundaClient client;
    private final Main.Window window;
    private final Path file;
    private Config config;
    private volatile String state = "Waiting for first tick";
    Schedule(CamundaClient c, Main.Window w, Path file) throws java.io.IOException {
        client=c; window=w; this.file=file;
        config=file != null && Files.exists(file) ? parse(JSON.readTree(Files.readAllBytes(file))) : DEFAULT;
    }
    static Config parse(JsonNode n) {
        if(n==null || !n.isObject() || n.size()!=3 || !n.has("enabled") || !n.get("enabled").isBoolean()
            || !n.has("start") || !n.get("start").isTextual() || !n.has("close") || !n.get("close").isTextual())
            throw new IllegalArgumentException("Expected enabled boolean, start string, close string; no extra fields");
        var c=new Config(n.get("enabled").booleanValue(),n.get("start").textValue(),n.get("close").textValue());
        period(c,Instant.EPOCH); return c;
    }
    static Period period(Config c, Instant now) {
        Matcher m=MONTH.matcher(c.start()), minutes=MINUTES.matcher(c.start());
        Instant start,next; String key;
        if(m.matches()) {
            var date=now.atZone(ZoneOffset.UTC).withDayOfMonth(Integer.parseInt(m.group(1))).withHour(Integer.parseInt(m.group(2))).withMinute(Integer.parseInt(m.group(3))).withSecond(0).withNano(0);
            if(date.toInstant().isAfter(now)) date=date.minusMonths(1);
            start=date.toInstant();next=date.plusMonths(1).toInstant();
            key="role-"+date.format(DateTimeFormatter.ofPattern("uuuu-MM"));
        } else if(minutes.matches() && Integer.parseInt(minutes.group(1))<=1440) {
            long seconds=60L*Integer.parseInt(minutes.group(1));
            start=Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(),seconds)*seconds);next=start.plusSeconds(seconds);
            key="role-"+start.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm"));
        } else throw new IllegalArgumentException("Invalid start rule (UTC)");
        return new Period(key,start,next,close(c,start,next));
    }
    static Instant close(Config c, Instant start, Instant next) {
        Matcher a=AFTER.matcher(c.close()), n=NEXT.matcher(c.close());
        if(a.matches()) {
            long unit=a.group(2).startsWith("minute")?60:a.group(2).startsWith("hour")?3600:86400;
            return start.plusSeconds(Long.parseLong(a.group(1))*unit);
        }
        if(n.matches() && MONTH.matcher(c.start()).matches()) {
            Instant end=next.atZone(ZoneOffset.UTC).withDayOfMonth(Integer.parseInt(n.group(1))).withHour(Integer.parseInt(n.group(2))).withMinute(Integer.parseInt(n.group(3))).toInstant();
            if(end.isAfter(start))return end;
        }
        throw new IllegalArgumentException("Invalid close rule; next period requires a month start rule");
    }
    synchronized void save(Config c) throws java.io.IOException {
        period(c,Instant.now());
        if(file!=null) {
            Files.createDirectories(file.getParent());
            Path temp=Files.createTempFile(file.getParent(),"schedule-",".json");
            try { JSON.writeValue(temp.toFile(),c); Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            finally { Files.deleteIfExists(temp); }
        }
        config=c;state=c.enabled()?"Waiting for next tick":"Off; open roles finish normally";
    }
    synchronized Map<String,Object> status(Instant now) {
        Period p=period(config,now), n=period(config,p.next());
        return Map.of("config",config,"effectiveState",config.enabled()?state:"Off; open roles finish normally",
            "now",now.toString(),"currentRoleKey",p.key(),"currentClose",p.close().toString(),"nextOpen",n.start().toString(),"nextClose",n.close().toString());
    }
    synchronized void tick(Instant now) {
        if(!config.enabled())return;
        try {
            Period p=period(config,now);
            var already=openKeys();
            if(!already.isEmpty()){state="Waiting: "+already.getFirst()+" is open";return;}
            var found=Instances.search(client,f->f.processDefinitionId(Main.process(window)).variables(Map.of("roleKey",p.key())));
            if(found.isEmpty())open(p.key(),p.close());
            state=found.isEmpty()?"Open requested: "+p.key():"Current period exists: "+p.key();
        } catch(RuntimeException e) {state="Scheduler request failed; retry on next tick";throw e;}
    }
    /** Opens a role the operator named, closing after the chosen number of minutes. One open role at a time. */
    synchronized String openNamed(String name, long closeAfterMinutes, Instant now) {
        if(name==null || !name.matches("[a-z0-9][a-z0-9-]{0,39}"))throw new IllegalArgumentException("Name: 1 to 40 characters, lower-case letters, digits and hyphens, not starting with a hyphen");
        if(closeAfterMinutes<1 || closeAfterMinutes>525600)throw new IllegalArgumentException("Close after: 1 minute to 365 days");
        var already=openKeys();
        if(!already.isEmpty())throw new IllegalArgumentException("A role is already open ("+already.getFirst()+"). Cancel it, or wait until it closes.");
        String key="role-manual-"+name;
        if(!Instances.search(client,f->f.processDefinitionId(Main.process(window)).variables(Map.of("roleKey",key))).isEmpty())
            throw new IllegalArgumentException("A role named "+name+" already exists. Choose another name.");
        open(key,now.plusSeconds(60*closeAfterMinutes));return key;
    }
    void open(String key, Instant deadline) {
        try {
            client.newPublishMessageCommand().messageName(window.message("role.open")).correlationKey(key).messageId(key)
                .timeToLive(Duration.ofSeconds(60)).variables(Map.of("roleKey",key,"deadline",deadline.toString())).send().join();
        } catch (io.camunda.client.api.command.ProblemException e) {
            // The message id is the role key: a repeated tick in the same period is rejected as ALREADY_EXISTS, which means the
            // role was already opened (the search that precedes the publish can lag behind the engine).
            if (e.details()==null || e.details().getStatus()==null || e.details().getStatus()!=409) throw e;
        }
    }
    /** Role keys that can still receive events: ACTIVE, the lane waits at race_a, deadline in the future; newest first. */
    List<String> openKeys() {
        var roles=Instances.search(client,f->f.processDefinitionId(Main.process(window)).state(ProcessInstanceState.ACTIVE));
        roles.sort(Comparator.comparing(io.camunda.client.api.search.response.ProcessInstance::getStartDate).reversed());
        var keys=new ArrayList<String>();
        for(var p:roles) {
            long id=p.getProcessInstanceKey();
            var deadlines=client.newVariableSearchRequest().filter(f->f.processInstanceKey(id).scopeKey(id).name("deadline")).send().join().items();
            // The lane waits at the event-based gateway race_a until the deadline timer or role.close fires (the catch events are not
            // element instances while it waits), so race_a ACTIVE means 'still open'; once triggered it is COMPLETED, so a role that
            // closed early or is draining is not selected again.
            var waits=client.newElementInstanceSearchRequest().filter(f->f.processInstanceKey(id).elementId("race_a").state(io.camunda.client.api.search.enums.ElementInstanceState.ACTIVE)).send().join().items();
            if(!deadlines.isEmpty() && Instant.parse(deadlines.getFirst().getValueAsType(String.class)).isAfter(Instant.now()) && !waits.isEmpty()) {
                var keyVar=client.newVariableSearchRequest().filter(f->f.processInstanceKey(id).scopeKey(id).name("roleKey")).send().join().items();
                if(!keyVar.isEmpty()) keys.add(keyVar.getFirst().getValueAsType(String.class));
            }
        }
        return keys;
    }
    String openKey() {
        var keys=openKeys();
        if(keys.isEmpty())throw new IllegalArgumentException("No open role");
        return keys.getFirst();
    }
    String closeNow() {
        String key=openKey();
        // Zeebe correlates a message once per BPMN process ID. Three independent
        // messages close the three competing subscriptions without interrupting work.
        for(int lane=0;lane<3;lane++) client.newPublishMessageCommand().messageName(window.message("role.close")).correlationKey(key).messageId(UUID.randomUUID().toString()).timeToLive(Duration.ofSeconds(60)).send().join();
        return key;
    }
}
