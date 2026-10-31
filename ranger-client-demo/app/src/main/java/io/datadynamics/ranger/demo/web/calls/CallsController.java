package io.datadynamics.ranger.demo.web.calls;

import io.datadynamics.ranger.demo.trace.HttpAttempt;
import io.datadynamics.ranger.demo.trace.SdkCalls;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Recent SDK calls and HTTP attempts, plus the starter's Micrometer metrics. */
@Controller
@RequestMapping("/calls")
public class CallsController {
    static final String REQUESTS = "ranger.client.requests";
    static final String RETRIES = "ranger.client.retries";

    private final SdkCalls calls;
    private final ObjectProvider<MeterRegistry> registry;

    public CallsController(SdkCalls calls, ObjectProvider<MeterRegistry> registry) {
        this.calls = calls;
        this.registry = registry;
    }

    public record TimerRow(String tags, long count, double meanMs, double maxMs) {
    }

    public record CounterRow(String tags, double count) {
    }

    @GetMapping
    public String page(@RequestParam(required = false) String client, Model model) {
        List<HttpAttempt> attempts = calls.recentAttempts();
        model.addAttribute("clients", attempts.stream().map(HttpAttempt::client).collect(Collectors.toCollection(TreeSet::new)));
        model.addAttribute("client", client);
        model.addAttribute("attempts", client == null || client.isBlank() ? attempts
                : attempts.stream().filter(a -> client.equals(a.client())).toList());
        model.addAttribute("recentCalls", calls.recentCalls());
        model.addAttribute("resultOpen", false);

        MeterRegistry r = registry.getIfAvailable();
        model.addAttribute("metricsAvailable", r != null);
        if (r != null) {
            model.addAttribute("timers", r.find(REQUESTS).timers().stream()
                    .map(t -> new TimerRow(tags(t), t.count(), t.mean(TimeUnit.MILLISECONDS), t.max(TimeUnit.MILLISECONDS)))
                    .sorted(Comparator.comparing(TimerRow::tags)).toList());
            model.addAttribute("retries", r.find(RETRIES).counters().stream()
                    .map(c -> new CounterRow(tags(c), c.count()))
                    .sorted(Comparator.comparing(CounterRow::tags)).toList());
        }
        return "calls/calls";
    }

    @PostMapping("/clear")
    public String clear() {
        calls.clear();
        return "redirect:/calls";
    }

    private static String tags(Meter m) {
        return m.getId().getTags().stream().map(t -> t.getKey() + "=" + t.getValue()).collect(Collectors.joining(", "));
    }
}
