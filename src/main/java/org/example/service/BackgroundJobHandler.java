package org.example.service;

import org.example.dto.BackgroundJobRecord;

public interface BackgroundJobHandler {

    String jobType();

    void handle(BackgroundJobRecord job) throws Exception;
}
