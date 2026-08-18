package com.cvijeticc.diffreview.model;

import java.util.Comparator;

/**
 * One review finding. id is "ruleId:path:line" and is the dedup key.
 * Field order here is the JSON field order, kept stable so SSE replays
 * are byte-identical.
 */
public record Finding(
        String id,
        String ruleId,
        String path,
        int line,
        String severity,
        String category,
        String title,
        String evidence
) {

    /** Contract ordering: path lexicographic, then line, then ruleId. */
    public static final Comparator<Finding> ORDER = Comparator
            .comparing(Finding::path)
            .thenComparingInt(Finding::line)
            .thenComparing(Finding::ruleId);

    public static Finding of(String ruleId, String path, int line, String severity,
                             String category, String title, String evidence) {
        return new Finding(ruleId + ":" + path + ":" + line, ruleId, path, line,
                severity, category, title, evidence);
    }
}
