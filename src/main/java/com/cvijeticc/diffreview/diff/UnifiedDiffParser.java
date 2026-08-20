package com.cvijeticc.diffreview.diff;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for unified diffs, both plain and git-style. It tracks new-file
 * line numbers for added/context lines and uses the hunk header line counts
 * to know where a hunk ends.
 *
 * <p>The counts are load-bearing: while they are still live, a removed line
 * whose content starts with two dashes renders as {@code --- x} and must be
 * consumed as hunk content, never mistaken for a file header. That is why
 * the strict, count-driven walk stays exactly as strict as it was.
 *
 * <p>What is deliberately tolerant is everything the counts cannot vouch
 * for, because a hand-written or generated diff gets those wrong far more
 * often than it gets the content wrong:
 * <ul>
 *   <li>counts that <em>undercount</em> the hunk - the tail is still consumed,
 *       because silently dropping added lines is worse than any parse error;</li>
 *   <li>counts that <em>overcount</em> it - the hunk ends cleanly at the next
 *       section or at end of input instead of failing the whole diff;</li>
 *   <li>a missing {@code ---}/{@code +++} pair - the path falls back to the
 *       {@code diff --git a/X b/X} header, and a lone {@code +++} is accepted.</li>
 * </ul>
 */
public final class UnifiedDiffParser {

    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");

    private UnifiedDiffParser() {
    }

    private static final class FileBuilder {
        String oldPath;
        String newPath;
        String gitPath;
        final List<Hunk> hunks = new ArrayList<>();
        final List<String> raw = new ArrayList<>();

        String resolvedPath() {
            if (newPath != null && !"/dev/null".equals(newPath)) {
                return stripPrefix(newPath, "b/");
            }
            if (oldPath != null && !"/dev/null".equals(oldPath)) {
                return stripPrefix(oldPath, "a/");
            }
            return gitPath; // "diff --git a/X b/X" without a ---/+++ pair
        }
    }

    public static List<DiffFile> parse(String diff) {
        if (diff == null || diff.isBlank()) {
            throw new DiffParseException("diff is empty");
        }
        String[] rawLines = diff.split("\n", -1);
        int end = rawLines.length;
        if (end > 0 && rawLines[end - 1].isEmpty()) {
            end--; // a trailing newline is not an extra line
        }

        List<DiffFile> files = new ArrayList<>();
        FileBuilder current = null;
        int i = 0;
        while (i < end) {
            String line = stripCr(rawLines[i]);
            if (line.startsWith("diff --git ")) {
                flush(files, current);
                current = new FileBuilder();
                current.gitPath = parseGitHeaderPath(line);
                current.raw.add(line);
                i++;
                continue;
            }
            if (line.startsWith("--- ")) {
                if (current == null || current.newPath != null || !current.hunks.isEmpty()) {
                    flush(files, current);
                    current = new FileBuilder();
                }
                current.oldPath = parsePath(line.substring(4));
                current.raw.add(line);
                i++;
                continue;
            }
            if (line.startsWith("+++ ")) {
                // A missing "---" is a malformed header, not a reason to lose the file.
                if (current == null || current.newPath != null || !current.hunks.isEmpty()) {
                    flush(files, current);
                    current = new FileBuilder();
                }
                current.newPath = parsePath(line.substring(4));
                current.raw.add(line);
                i++;
                continue;
            }
            Matcher hm = HUNK_HEADER.matcher(line);
            if (hm.matches()) {
                if (current == null || current.resolvedPath() == null) {
                    throw new DiffParseException("hunk header before any file header at line " + (i + 1));
                }
                current.raw.add(line);
                int newStart = Integer.parseInt(hm.group(3));
                int oldRemaining = hm.group(2) != null ? Integer.parseInt(hm.group(2)) : 1;
                int newRemaining = hm.group(4) != null ? Integer.parseInt(hm.group(4)) : 1;
                List<DiffLine> lines = new ArrayList<>();
                int newNumber = newStart;
                i++;
                while (i < end) {
                    String hl = stripCr(rawLines[i]);
                    if (hl.startsWith("\\")) { // "\ No newline at end of file"
                        current.raw.add(hl);
                        i++;
                        continue;
                    }
                    if (oldRemaining > 0 || newRemaining > 0) {
                        // Only lines that can never be hunk content end the hunk while
                        // the counts run; "--- x" / "+++ x" are still removed/added lines.
                        if (isUnambiguousSectionStart(hl)) {
                            break;
                        }
                    } else if (startsNewSection(hl) || !isHunkContent(hl)) {
                        break; // counts exhausted and this is genuinely past the hunk
                    }
                    char kind = hl.isEmpty() ? ' ' : hl.charAt(0);
                    String content = hl.isEmpty() ? "" : hl.substring(1);
                    switch (kind) {
                        case ' ' -> {
                            lines.add(new DiffLine(' ', content, newNumber++));
                            oldRemaining--;
                            newRemaining--;
                        }
                        case '+' -> {
                            lines.add(new DiffLine('+', content, newNumber++));
                            newRemaining--;
                        }
                        case '-' -> {
                            lines.add(new DiffLine('-', content, -1));
                            oldRemaining--;
                        }
                        default -> throw new DiffParseException("malformed hunk line at line " + (i + 1));
                    }
                    current.raw.add(hl);
                    i++;
                }
                // Unsatisfied counts end the hunk with whatever was actually there:
                // the content is the truth, the declared count is only a hint.
                current.hunks.add(new Hunk(newStart, List.copyOf(lines)));
                continue;
            }
            // git metadata (index, mode changes, Binary files ...) or leading junk
            if (current != null) {
                current.raw.add(line);
            }
            i++;
        }
        flush(files, current);
        if (files.isEmpty()) {
            throw new DiffParseException("not a unified diff: no file section with at least one hunk found");
        }
        return files;
    }

    /** Lines that cannot be hunk content, because hunk content always starts with '+', '-' or ' '. */
    private static boolean isUnambiguousSectionStart(String l) {
        return l.startsWith("diff --git ") || HUNK_HEADER.matcher(l).matches();
    }

    private static boolean startsNewSection(String l) {
        return l.startsWith("--- ") || l.startsWith("+++ ") || isUnambiguousSectionStart(l);
    }

    private static boolean isHunkContent(String l) {
        if (l.isEmpty()) {
            return true; // an empty line is an empty context line
        }
        char c = l.charAt(0);
        return c == '+' || c == '-' || c == ' ';
    }

    private static void flush(List<DiffFile> files, FileBuilder b) {
        if (b != null && b.resolvedPath() != null && !b.hunks.isEmpty()) {
            files.add(new DiffFile(b.resolvedPath(), List.copyOf(b.hunks), String.join("\n", b.raw)));
        }
    }

    /** "diff --git a/src/db.ts b/src/db.ts" to "src/db.ts"; used when ---/+++ are missing. */
    private static String parseGitHeaderPath(String line) {
        String rest = line.substring("diff --git ".length()).strip();
        int mid = rest.indexOf(" b/");
        if (mid > 0) {
            return stripPrefix(parsePath(rest.substring(mid + 1)), "b/");
        }
        int space = rest.lastIndexOf(' ');
        if (space > 0) {
            return stripPrefix(parsePath(rest.substring(space + 1)), "b/");
        }
        return null;
    }

    private static String parsePath(String s) {
        String p = s;
        int tab = p.indexOf('\t');
        if (tab >= 0) {
            p = p.substring(0, tab); // "+++ path<TAB>timestamp" form
        }
        p = p.strip();
        if (p.length() >= 2 && p.startsWith("\"") && p.endsWith("\"")) {
            p = p.substring(1, p.length() - 1);
        }
        return p;
    }

    private static String stripPrefix(String path, String prefix) {
        return path.startsWith(prefix) ? path.substring(prefix.length()) : path;
    }

    private static String stripCr(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }
}
