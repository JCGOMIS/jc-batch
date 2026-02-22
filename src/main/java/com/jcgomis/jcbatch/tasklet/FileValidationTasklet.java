package com.jcgomis.jcbatch.tasklet;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;

@Slf4j
@Component
@RequiredArgsConstructor
public class FileValidationTasklet implements Tasklet {

    @Value("${batch.input-file:classpath:data/transactions.csv}")
    private Resource inputFile;

    @Override
    public @Nullable RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        log.info("Validation du fichier d'entrée...");

        // 1. Vérifier l'existence du fichier
        if (!inputFile.exists()) {
            log.error("Le fichier d'entrée n'existe pas : {}", inputFile);
            contribution.setExitStatus(ExitStatus.FAILED);
            return RepeatStatus.FINISHED;
        }

        // 2. Vérifier que le fichier est lisible et non vide
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputFile.getInputStream()))) {

            String header = reader.readLine();
            if (header == null || header.isBlank()) {
                log.error("Le fichier est vide (pas d'en-tête)");
                contribution.setExitStatus(ExitStatus.FAILED);
                return RepeatStatus.FINISHED;
            }

            // 3. Vérifier qu'il y a au moins une ligne de données
            String firstDataLine = reader.readLine();
            if (firstDataLine == null || firstDataLine.isBlank()) {
                log.error("Le fichier ne contient aucune donnée (en-tête uniquement)");
                contribution.setExitStatus(ExitStatus.FAILED);
                return RepeatStatus.FINISHED;
            }

            // Compter le nombre total de lignes pour le log
            long lineCount = 2; // header + première ligne déjà lues
            while (reader.readLine() != null) {
                lineCount++;
            }

            log.info("Fichier valide : {} lignes détectées ({} lignes de données)",
                    lineCount, lineCount - 1);

            // Stocker le nombre de lignes dans le contexte (accessible par les steps suivants)
            chunkContext.getStepContext()
                    .getStepExecution()
                    .getJobExecution()
                    .getExecutionContext()
                    .putLong("totalLines", lineCount - 1);

        } catch (Exception e) {
            log.error("Erreur lors de la lecture du fichier : {}", e.getMessage());
            contribution.setExitStatus(ExitStatus.FAILED);
            return RepeatStatus.FINISHED;
        }

        contribution.setExitStatus(ExitStatus.COMPLETED);
        return RepeatStatus.FINISHED;
    }
}
