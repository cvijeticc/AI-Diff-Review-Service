package com.cvijeticc.diffreview.diff;

import java.util.List;

public record Hunk(int newStart, List<DiffLine> lines) {
}
