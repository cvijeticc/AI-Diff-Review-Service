package com.cvijeticc.diffreview.diff;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for unified diffs, both plain and git-style. It tracks new-file
 * line numbers for added/context lines, uses the hunk header line counts to
 * know exactly where a hunk ends (so removed lines starting with "---" can
 * never be mistaken for a file header), and keeps the raw text of each file
 * section for chunk sizing.
 */
public final class UnifiedDiffParser {

    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");

    private UnifiedDiffParser() {
    }

    private static final class FileBuilder {
        String oldPath;
        String newPath;
        final List<Hunk> hunks = new ArrayList<>();
        final List<String> raw = new ArrayList<>();

        String resolvedPath() {
            if (newPath != null && !"/dev/null".equals(newPath)) {
                return stripPrefix(newPath, "b/");
            }
            if (oldPath != null && !"/dev/null".equals(oldPath)) {
                return stripPrefix(oldPath, "a/");
            }
            return null;
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
                if (current == null || current.oldPath == null) {
                    throw new DiffParseException("+++ header without a matching --- header at line " + (i + 1));
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
                while ((oldRemaining > 0 || newRemaining > 0) && i < end) {
                    String hl = stripCr(rawLines[i]);
                    if (hl.startsWith("\\")) { // "\ No newline at end of file"
                        current.raw.add(hl);
                        i++;
                        continue;
                    }
                    char kind = hl.isEmpty() ? ' ' : hl.charAt(0);
                    String content = hl.isEmpty() ? "" : hl.substring(1);
                    switch (kind) {
                        case ' ' -> {
                            if (oldRemaining <= 0 || newRemaining <= 0) {
                                throw new DiffParseException("hunk line counts do not match content at line " + (i + 1));
                            }
                            lines.add(new DiffLine(' ', content, newNumber++));
                            oldRemaining--;
                            newRemaining--;
                        }
                        case '+' -> {
                            if (newRemaining <= 0) {
                                throw new DiffParseException("hunk line counts do not match content at line " + (i + 1));
                            }
                            lines.add(new DiffLine('+', content, newNumber++));
                            newRemaining--;
                        }
                        case '-' -> {
                            if (oldRemaining <= 0) {
                                throw new DiffParseException("hunk line counts do not match content at line " + (i + 1));
                            }
                            lines.add(new DiffLine('-', content, -1));
                            oldRemaining--;
                        }
                        default -> throw new DiffParseException("malformed hunk line at line " + (i + 1));
                    }
                    current.raw.add(hl);
                    i++;
                }
                if (oldRemaining > 0 || newRemaining > 0) {
                    throw new DiffParseException("truncated hunk: line counts not satisfied");
                }
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

    private static void flush(List<DiffFile> files, FileBuilder b) {
        if (b != null && b.resolvedPath() != null && !b.hunks.isEmpty()) {
            files.add(new DiffFile(b.resolvedPath(), List.copyOf(b.hunks), String.join("\n", b.raw)));
        }
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
