package com.cvijeticc.diffreview.provider;

import com.cvijeticc.diffreview.diff.DiffFile;
import com.cvijeticc.diffreview.diff.DiffLine;
import com.cvijeticc.diffreview.diff.Hunk;
import com.cvijeticc.diffreview.model.Finding;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Deterministic provider implementing the scored rules table exactly.
 * Rules run against added lines only; line numbers refer to the new file;
 * one finding per matching line per rule. Injection content (MOCK-INJ) is
 * reported as a finding and treated as inert text - nothing in a diff can
 * change how this scanner behaves, because the diff is only ever data here.
 */
@Component
public class MockReviewProvider implements ReviewProvider {

    private static final Pattern CREDENTIAL = Pattern.compile(
            "(api[_-]?key|secret|token)\\s*[:=]\\s*['\"][A-Za-z0-9_\\-]{16,}['\"]",
            Pattern.CASE_INSENSITIVE);

    // Excludes strict equality: "=== null" and "!== null" are not loose comparisons.
    private static final Pattern LOOSE_NULL = Pattern.compile("(?<![=!])[!=]=\\s*null");

    private static final Pattern DEEP_CLONE = Pattern.compile("JSON\\.parse\\(\\s*JSON\\.stringify\\(");

    // A string literal containing a SQL keyword; concatenation with + is checked around the match.
    private static final Pattern SQL_STRING = Pattern.compile(
            "[\"'][^\"']*\\b(SELECT|INSERT|UPDATE|DELETE)\\b[^\"']*[\"']");

    private static final Pattern CATCH_CLAUSE = Pattern.compile("\\bcatch\\b\\s*(\\([^)]*\\))?\\s*");

    private static final Pattern INJECTION = Pattern.compile(
            "ignore previous instructions|disregard all prior|you are now",
            Pattern.CASE_INSENSITIVE);

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public List<Finding> review(List<DiffFile> chunk) {
        List<Finding> out = new ArrayList<>();
        for (DiffFile file : chunk) {
            for (Hunk hunk : file.hunks()) {
                scanHunk(file.path(), hunk, out);
            }
        }
        return out;
    }

    private void scanHunk(String path, Hunk hunk, List<Finding> out) {
        List<DiffLine> lines = hunk.lines();
        for (int i = 0; i < lines.size(); i++) {
            DiffLine dl = lines.get(i);
            if (dl.type() != '+') {
                continue;
            }
            String s = dl.content();
            int line = dl.newLineNumber();
            if (s.contains("eval(")) {
                out.add(Finding.of("MOCK-001", path, line, "critical", "security", "eval usage", s));
            }
            if (CREDENTIAL.matcher(s).find()) {
                out.add(Finding.of("MOCK-002", path, line, "critical", "security", "hardcoded credential", s));
            }
            if (sqlConcatenation(s)) {
                out.add(Finding.of("MOCK-003", path, line, "high", "security", "SQL string concatenation", s));
            }
            if (isEmptyCatch(lines, i)) {
                out.add(Finding.of("MOCK-004", path, line, "high", "correctness", "swallowed exception", s));
            }
            if (LOOSE_NULL.matcher(s).find()) {
                out.add(Finding.of("MOCK-005", path, line, "medium", "correctness", "loose null comparison", s));
            }
            if (DEEP_CLONE.matcher(s).find()) {
                out.add(Finding.of("MOCK-006", path, line, "medium", "performance", "deep-clone via JSON", s));
            }
            if (s.contains("console.log(")) {
                out.add(Finding.of("MOCK-007", path, line, "low", "style", "console.log left in", s));
            }
            if (s.contains("TODO") || s.contains("FIXME")) {
                out.add(Finding.of("MOCK-008", path, line, "low", "style", "unresolved marker", s));
            }
            if (INJECTION.matcher(s).find()) {
                out.add(Finding.of("MOCK-INJ", path, line, "critical", "security", "prompt-injection content", s));
            }
        }
    }

    /** SQL keyword inside a string literal that is concatenated with +. */
    private static boolean sqlConcatenation(String s) {
        Matcher m = SQL_STRING.matcher(s);
        while (m.find()) {
            int after = m.end();
            while (after < s.length() && Character.isWhitespace(s.charAt(after))) {
                after++;
            }
            if (after < s.length() && s.charAt(after) == '+') {
                return true;
            }
            int before = m.start() - 1;
            while (before >= 0 && Character.isWhitespace(s.charAt(before))) {
                before--;
            }
            if (before >= 0 && s.charAt(before) == '+') {
                return true;
            }
            if (before >= 1 && s.charAt(before) == '=' && s.charAt(before - 1) == '+') {
                return true; // += concatenation
            }
        }
        return false;
    }

    /**
     * Empty catch block detection. The block may span lines; the finding is
     * reported on the catch line. Emptiness is judged on the new file, so
     * context lines inside the block count as content.
     */
    private static boolean isEmptyCatch(List<DiffLine> lines, int idx) {
        String s = lines.get(idx).content();
        Matcher m = CATCH_CLAUSE.matcher(s);
        while (m.find()) {
            Boolean result = emptyBlockFrom(s.substring(m.end()), lines, idx);
            if (result != null) {
                return result;
            }
        }
        return false;
    }

    /** rest is the text after the catch clause on the same line; null means "not a catch block here". */
    private static Boolean emptyBlockFrom(String rest, List<DiffLine> lines, int idx) {
        if (rest.startsWith("{")) {
            return bodyEmpty(rest.substring(1), lines, idx);
        }
        if (!rest.isBlank()) {
            return null;
        }
        for (int j = idx + 1; j < lines.size(); j++) {
            DiffLine dl = lines.get(j);
            if (dl.type() == '-') {
                continue;
            }
            String t = dl.content().strip();
            if (t.isEmpty()) {
                continue;
            }
            if (t.startsWith("{")) {
                return bodyEmpty(t.substring(1), lines, j);
            }
            return null;
        }
        return null;
    }

    /** afterBrace is the text following the opening brace. */
    private static Boolean bodyEmpty(String afterBrace, List<DiffLine> lines, int idx) {
        String t = afterBrace.strip();
        if (t.startsWith("}")) {
            return true;
        }
        if (!t.isEmpty()) {
            return false;
        }
        for (int j = idx + 1; j < lines.size(); j++) {
            DiffLine dl = lines.get(j);
            if (dl.type() == '-') {
                continue;
            }
            String u = dl.content().strip();
            if (u.isEmpty()) {
                continue;
            }
            return u.startsWith("}");
        }
        return false;
    }
}
