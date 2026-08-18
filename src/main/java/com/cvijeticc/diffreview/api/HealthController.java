package com.cvijeticc.diffreview.api;

import com.cvijeticc.diffreview.config.AppProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final AppProperties props;
    private final Instant startedAt = Instant.now();

    public HealthController(AppProperties props) {
        this.props = props;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("version", props.version());
        out.put("uptimeSeconds", Duration.between(startedAt, Instant.now()).getSeconds());
        return out;
    }
}
