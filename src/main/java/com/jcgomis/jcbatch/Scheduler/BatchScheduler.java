package com.jcgomis.jcbatch.Scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Scheduler qui déclenche le batch selon une expression cron.
 *
 * Activation via la propriété : batch.scheduler.enabled=true
 * (désactivé par défaut pour ne pas interférer avec les tests)
 *
 * Expression cron configurable dans application.yaml :
 *   batch.scheduler.cron: "0 0 2 * * *"  → tous les jours à 2h du matin
 *
 * Alternatives de scheduling en production :
 * - Kubernetes CronJob : pour les environnements containerisés
 * - Spring Cloud Data Flow : orchestration avancée de batchs
 * - Ordonnanceurs entreprise : Control-M, Autosys, Tivoli
 * - Quartz Scheduler : pour du clustering et de la persistance
 */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(name = "batch.scheduler.enabled", havingValue = "true")
public class BatchScheduler {

    private final JobOperator jobOperator;
    private final Job importTransactionsJob;

    /**
     * Déclenche le job d'import selon le cron configuré.
     *
     * Chaque exécution reçoit un timestamp unique dans ses JobParameters,
     * ce qui permet à Spring Batch de créer une nouvelle JobInstance
     * à chaque déclenchement (sinon, il considérerait que le job a
     * déjà été exécuté avec les mêmes paramètres).
     */
    @Scheduled(cron = "${batch.scheduler.cron:0 0 2 * * *}")
    public void runImportJob() {

        log.info("Scheduler : déclenchement du job d'import à {}", LocalDateTime.now());

        try {
            JobParameters params = new JobParametersBuilder()
                    .addLocalDateTime("scheduledAt", LocalDateTime.now())
                    .addString("triggeredBy", "scheduler")
                    .toJobParameters();

            jobOperator.start(importTransactionsJob, params);

            log.info("Scheduler : job terminé avec succès");

        } catch (Exception e) {
            log.error("Scheduler : échec du lancement du job — {}", e.getMessage(), e);
            // En production : alerte PagerDuty, email, etc.
        }
    }
}
