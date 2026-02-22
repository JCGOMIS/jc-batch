package com.jcgomis.jcbatch.tasklet;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

/**
 * Tasklet de notification d'erreur.
 *
 * Exécuté lorsqu'un step précédent échoue (via flow conditionnel).
 *
 * En production, cette tasklet pourrait :
 * - Envoyer un email d'alerte via JavaMailSender
 * - Poster un message Slack/Teams via WebClient
 * - Créer un incident dans ServiceNow / PagerDuty
 * - Publier un événement Kafka pour le monitoring
 */
@Slf4j
@Component
public class ErrorNotificationTasklet implements Tasklet {

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {

        String jobName = chunkContext.getStepContext().getJobName();
        long jobExecutionId = chunkContext.getStepContext()
                .getStepExecution()
                .getJobExecutionId();

        log.error("╔══════════════════════════════════════════════╗");
        log.error("║  ⚠️  ALERTE — ÉCHEC DU BATCH                ║");
        log.error("╠══════════════════════════════════════════════╣");
        log.error("║  Job       : {}", jobName);
        log.error("║  Execution : {}", jobExecutionId);
        log.error("║  Action    : Notification envoyée            ║");
        log.error("╚══════════════════════════════════════════════╝");

        // Exemple : envoi d'email
        // mailSender.send(buildAlertMessage(jobName, jobExecutionId));

        // Exemple : notification Slack
        // webClient.post().uri(slackWebhookUrl)
        //     .bodyValue(Map.of("text", "Batch " + jobName + " en échec"))
        //     .retrieve().toBodilessEntity().block();

        return RepeatStatus.FINISHED;
    }
}

