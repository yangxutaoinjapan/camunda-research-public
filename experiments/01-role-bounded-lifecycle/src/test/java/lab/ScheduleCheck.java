package lab;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;

/** Offline arithmetic/validation/persistence regression checks; no engine. */
public final class ScheduleCheck {
    static int checks;
    static void equal(Object a,Object b){checks++;if(!a.equals(b))throw new AssertionError(a+" != "+b);}
    public static void main(String[] args) throws Exception {
        var monthly=new Schedule.Config(true,"day of month 28 at 23:59","at day of month 1 00:00 of the next period");
        var p=Schedule.period(monthly,Instant.parse("2024-03-01T12:00:00Z"));
        equal(p.key(),"role-2024-02");equal(p.start(),Instant.parse("2024-02-28T23:59:00Z"));
        equal(p.close(),Instant.parse("2024-03-01T00:00:00Z"));
        p=Schedule.period(Schedule.DEFAULT,Instant.parse("2024-12-31T23:59:59Z"));
        equal(p.next(),Instant.parse("2025-01-01T00:00:00Z"));equal(p.close(),p.next());
        var minute=new Schedule.Config(true,"every 7 minutes","after 2 hours");
        p=Schedule.period(minute,Instant.parse("2026-11-02T11:23:45Z"));
        equal(p.start().getEpochSecond()%420,0L);equal(Duration.between(p.start(),p.next()),Duration.ofMinutes(7));
        equal(Duration.between(p.start(),p.close()),Duration.ofHours(2));
        equal(Schedule.period(new Schedule.Config(true,"every 1440 minutes","after 1 days"),Instant.EPOCH).close(),Instant.EPOCH.plusSeconds(86400));
        equal(Schedule.period(new Schedule.Config(true,"every 1 minute","after 1 minute"),Instant.EPOCH).close(),Instant.EPOCH.plusSeconds(60));
        equal(Schedule.period(new Schedule.Config(true,"every 1 minute","after 1 hour"),Instant.EPOCH).close(),Instant.EPOCH.plusSeconds(3600));
        var json=new ObjectMapper();
        for(String bad:new String[]{"{}","{\"enabled\":1,\"start\":\"every 1 minutes\",\"close\":\"after 1 minutes\"}",
            "{\"enabled\":true,\"start\":\"every 1441 minutes\",\"close\":\"after 1 minutes\"}",
            "{\"enabled\":true,\"start\":\"day of month 29 at 00:00\",\"close\":\"after 1 minutes\"}",
            "{\"enabled\":true,\"start\":\"day of month 1 at 24:00\",\"close\":\"after 1 minutes\"}",
            "{\"enabled\":false,\"start\":\"every 1 minutes\",\"close\":\"after 0 minutes\"}",
            "{\"enabled\":false,\"start\":\"every 1 minutes\",\"close\":\"at day of month 1 00:00 of the next period\"}"}) {
            try {Schedule.parse(json.readTree(bad));throw new AssertionError(bad);}catch(IllegalArgumentException expected){checks++;}
        }
        Path dir=Files.createTempDirectory("schedule-check-");Path file=dir.resolve("schedule.json");
        try {
            var schedule=new Schedule(null,Main.Window.MONTH,file);
            equal(schedule.status(Instant.EPOCH).get("config"),Schedule.DEFAULT);
            schedule.save(minute);equal(new Schedule(null,Main.Window.MONTH,file).status(Instant.EPOCH).get("config"),minute);
            try(var entries=Files.list(dir)){equal(entries.count(),1L);}
        }finally{Files.deleteIfExists(file);Files.deleteIfExists(dir);}
        System.out.println("PASS: "+checks+" schedule checks");
    }
}
