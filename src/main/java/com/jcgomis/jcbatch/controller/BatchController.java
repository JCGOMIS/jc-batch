package com.jcgomis.jcbatch.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


import java.time.LocalDateTime;
import java.util.Map;

/**
 * Contrôleur REST pour déclencher le batch manuellement.
 *
 * POST /api/batch/run → lance le job d'import
 *
 * Complémentaire au scheduler : permet de relancer un batch
 * à la demande (via Postman, curl, ou un outil d'admin).
 *
 * curl -X POST http://localhost:8080/api/batch/run
 */
@Slf4j
@RestController
@RequestMapping("/api/batch")
@RequiredArgsConstructor
public class BatchController {

    @Autowired
    private final JobOperator jobOperator;
    private final Job importTransactionsJob;

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runBatch() {

        log.info("Déclenchement manuel du batch via API REST");

        try {
            JobParameters params = new JobParametersBuilder()
                    .addLocalDateTime("triggeredAt", LocalDateTime.now())
                    .addString("triggeredBy", "api-rest")
                    .toJobParameters();

            JobExecution execution = jobOperator.start(importTransactionsJob, params);

            return ResponseEntity.ok(Map.of(
                    "jobId", execution.getId(),
                    "status", execution.getStatus().toString(),
                    "startTime", execution.getStartTime() != null
                            ? execution.getStartTime().toString() : "N/A",
                    "endTime", execution.getEndTime() != null
                            ? execution.getEndTime().toString() : "N/A"
            ));

        } catch (Exception e) {
            log.error("Erreur lors du lancement du batch : {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", e.getMessage(),
                    "status", "FAILED"
            ));
        }
    }
}
