package com.cvijeticc.diffreview.provider;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class ProviderRegistry {

    private final Map<String, ReviewProvider> providers;

    public ProviderRegistry(List<ReviewProvider> all) {
        this.providers = all.stream().collect(Collectors.toMap(ReviewProvider::name, p -> p));
    }

    public ReviewProvider get(String name) {
        ReviewProvider p = providers.get(name);
        if (p == null) {
            throw new IllegalArgumentException("unknown provider: " + name);
        }
        return p;
    }

    public List<String> names() {
        return providers.keySet().stream().sorted().toList();
    }
}
