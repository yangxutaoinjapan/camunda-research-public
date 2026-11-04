package lab;

import java.io.InputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** Offline structure checks of the shipped BPMN files; no engine. Children are driven by messages only. */
public final class BpmnCheck {
    static Document load(String name) throws Exception {
        try (InputStream in = BpmnCheck.class.getResourceAsStream("/bpmn/" + name)) {
            if (in == null) throw new AssertionError("missing " + name);
            var f = DocumentBuilderFactory.newInstance(); f.setNamespaceAware(true);
            return f.newDocumentBuilder().parse(in);
        }
    }
    static int count(Document d, String tag) { return d.getElementsByTagNameNS("*", tag).getLength(); }
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    public static void main(String[] args) throws Exception {
        for (String lane : new String[]{"a", "b", "c"}) {
            var d = load("lab-child-" + lane + ".bpmn");
            check(count(d, "serviceTask") == 0, "child " + lane + " has no service task (nothing claims a child)");
            check(count(d, "timerEventDefinition") == 0, "child " + lane + " has no timer");
            check(count(d, "messageEventDefinition") == 1, "child " + lane + " waits at exactly one message catch");
            var m = (Element) d.getElementsByTagNameNS("*", "message").item(0);
            check(("work." + lane + ".done").equals(m.getAttribute("name")), "child " + lane + " finish message name");
            check(m.getElementsByTagNameNS("*", "subscription").item(0) instanceof Element s && "= childId".equals(s.getAttribute("correlationKey")), "child " + lane + " correlates on childId");
        }
        var role = load("worker-role-monthly.bpmn");
        check(count(role, "serviceTask") == 0, "role has no service task");
        check(count(role, "timerEventDefinition") == 3, "role keeps its three deadline timers");
        int mapped = 0;
        var inputs = role.getElementsByTagNameNS("*", "input");
        for (int i = 0; i < inputs.getLength(); i++) if ("childId".equals(((Element) inputs.item(i)).getAttribute("target"))) mapped++;
        check(mapped == 3, "each call activity maps childId into its child");
        System.out.println("BpmnCheck ok");
    }
}
