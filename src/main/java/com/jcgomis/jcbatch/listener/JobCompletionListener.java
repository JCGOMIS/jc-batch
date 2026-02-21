package com.jcgomis.jcbatch.listener;



import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Listener au niveau Job : rapport d'exécution complet.
 *
 * En production, ce listener pourrait :
 * - Envoyer une notification Slack/email
 * - Publier des métriques vers Prometheus/Grafana
 * - Mettre à jour un tableau de bord de supervision
 */
@Slf4j
@Component
public class JobCompletionListener implements JobExecutionListener {

    @Override
    public void beforeJob(JobExecution jobExecution) {
        log.info("╔══════════════════════════════════════════════════╗");
        log.info("║  DÉMARRAGE DU JOB : {}",
                String.format("%-27s ║", jobExecution.getJobInstance().getJobName()));
        log.info("║  Paramètres : {}",
                String.format("%-33s ║",
                        jobExecution.getJobParameters().toString().substring(0,
                                Math.min(33, jobExecution.getJobParameters().toString().length()))));
        log.info("╚══════════════════════════════════════════════════╝");
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        Duration duration = Duration.between(
                jobExecution.getStartTime(), jobExecution.getEndTime());

        log.info("╔══════════════════════════════════════════════════╗");
        log.info("║  RAPPORT D'EXÉCUTION DU JOB                     ║");
        log.info("╠══════════════════════════════════════════════════╣");
        log.info("║  Statut      : {}", formatStatus(jobExecution.getStatus()));
        log.info("║  Durée       : {} ms", duration.toMillis());

        for (StepExecution stepExecution : jobExecution.getStepExecutions()) {
            log.info("╠──────────────────────────────────────────────────╣");
            log.info("║  Step        : {}", stepExecution.getStepName());
            log.info("║  Lus         : {}", stepExecution.getReadCount());
            log.info("║  Filtrés     : {}", stepExecution.getFilterCount());
            log.info("║  Écrits      : {}", stepExecution.getWriteCount());
            log.info("║  Skippés     : {}", stepExecution.getSkipCount());
            log.info("║  Commits     : {}", stepExecution.getCommitCount());
            log.info("║  Rollbacks   : {}", stepExecution.getRollbackCount());
        }

        log.info("╚══════════════════════════════════════════════════╝");

        if (jobExecution.getStatus() == BatchStatus.FAILED) {
            log.error("Le job a échoué. Exceptions :");
            jobExecution.getAllFailureExceptions()
                    .forEach(ex -> log.error("  → {}", ex.getMessage()));
        }
    }

    private String formatStatus(BatchStatus status) {
        return switch (status) {
            case COMPLETED -> "✅ COMPLETED";
            case FAILED -> "❌ FAILED";
            case STOPPED -> "⏹ STOPPED";
            case STARTED -> "▶ STARTED";
            default -> status.toString();
        };
    }
}
