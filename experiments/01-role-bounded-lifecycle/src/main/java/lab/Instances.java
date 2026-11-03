package lab;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.enums.ProcessInstanceState;
import io.camunda.client.api.search.filter.ProcessInstanceFilter;
import io.camunda.client.api.search.response.ProcessInstance;
import java.util.*;
import java.util.function.Consumer;

/** Engine-recorded process instances, including ancestry; never element-scope counts. */
final class Instances {
    static boolean hasParent(ProcessInstance p) {
        // The REST client may represent an absent engine key as null or the -1 sentinel.
        return p.getParentProcessInstanceKey() != null && p.getParentProcessInstanceKey() > 0;
    }
    static List<ProcessInstance> search(CamundaClient c, Consumer<ProcessInstanceFilter> filter) {
        var all = new ArrayList<ProcessInstance>();
        for (int offset = 0; ; offset += 100) {
            final int from = offset;
            var page = c.newProcessInstanceSearchRequest().filter(filter)
                .sort(s -> s.processInstanceKey().asc()).page(p -> p.from(from).limit(100)).send().join().items();
            all.addAll(page);
            if (page.size() < 100) return all;
        }
    }
    static List<ProcessInstance> children(CamundaClient c, String lane, long parent, ProcessInstanceState state) {
        return search(c, f -> {
            f.processDefinitionId("lab-child-" + lane).parentProcessInstanceKey(parent);
            if (state != null) f.state(state);
        });
    }
    static Map<String, Long> summary(CamundaClient c) {
        long roots = 0, children = 0;
        for (String definition : List.of("worker-role-monthly", "worker-role-minute", "lab-child-a", "lab-child-b", "lab-child-c"))
            for (var p : search(c, f -> f.processDefinitionId(definition))) {
                if (hasParent(p)) children++; else roots++;
            }
        return Map.of("ROOT", roots, "CHILD", children);
    }
}
