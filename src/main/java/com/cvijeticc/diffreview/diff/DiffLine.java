package com.cvijeticc.diffreview.diff;

/**
 * One line inside a hunk. type is one of ' ' (context), '+' (added),
 * '-' (removed). newLineNumber is the line number in the NEW file for
 * context/added lines, -1 for removed lines.
 */
public record DiffLine(char type, String content, int newLineNumber) {
}
