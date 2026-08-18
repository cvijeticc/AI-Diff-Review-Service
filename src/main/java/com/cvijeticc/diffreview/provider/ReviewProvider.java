package com.cvijeticc.diffreview.provider;

import com.cvijeticc.diffreview.diff.DiffFile;
import com.cvijeticc.diffreview.model.Finding;
import java.util.List;

/**
 * A provider reviews one chunk (a list of parsed file sections) and returns
 * raw findings. The pipeline around it owns chunk iteration, dedup by id,
 * ordering and maxFindings truncation, so mock and llm behave identically
 * with respect to all cross-cutting behaviors.
 */
public interface ReviewProvider {

    String name();

    List<Finding> review(List<DiffFile> chunk) throws Exception;
}
