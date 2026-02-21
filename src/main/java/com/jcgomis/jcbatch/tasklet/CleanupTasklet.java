package com.jcgomis.jcbatch.tasklet;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

/**
 * Exemple de Tasklet : alternative au chunk processing pour des
 * opérations simples qui ne suivent pas le pattern Read-Process-Write.
 *
 * Cas d'usage typiques :
 * - Nettoyage de fichiers temporaires
 * - Envoi de notifications (email, Slack)
 * - Appel API ponctuel
 * - Vérification de pré-conditions
 * - Archivage de fichiers traités
 *
 * Pour l'intégrer dans le Job :
 *
 *   @Bean
 *   public Step cleanupStep(JobRepository jobRepository,
 *                            PlatformTransactionManager txManager) {
 *       return new StepBuilder("cleanupStep", jobRepository)
 *               .tasklet(cleanupTasklet, txManager)
 *               .build();
 *   }
 *
 *   // Puis dans le Job :
 *   .start(importStep)
 *   .next(cleanupStep)
 */
@Slf4j
@Component
public class CleanupTasklet implements Tasklet {

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {

        String jobName = chunkContext.getStepContext()
                .getJobName();

        log.info("Nettoyage post-import pour le job : {}", jobName);

        // Exemple : archivage du fichier CSV traité
        // Files.move(source, archiveDir.resolve(source.getFileName()));

        // Exemple : notification
        // notificationService.send("Import terminé avec succès");

        log.info("Nettoyage terminé.");

        // FINISHED = exécuter une seule fois
        // CONTINUABLE = ré-exécuter (boucle)
        return RepeatStatus.FINISHED;
    }
}