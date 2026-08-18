package com.cvijeticc.diffreview.jobs;

public enum JobStatus {
    QUEUED, RUNNING, DONE, FAILED;

    public String json() {
        return name().toLowerCase();
    }
}
