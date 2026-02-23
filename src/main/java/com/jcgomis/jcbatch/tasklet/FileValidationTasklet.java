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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;

@Slf4j
@Component
@RequiredArgsConstructor
public class FileValidationTasklet implements Tasklet {

    @Value("${batch.input-file:classpath:data/transactions.csv}")
    private Resource inputFile;

    private final JdbcTemplate jdbcTemplate;  // ← ajouter l'injection

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {

        // 1. Nettoyage de la table avant import
        jdbcTemplate.execute("TRUNCATE TABLE transactions");
        log.info("Table transactions vidée avant import");

        // 2. Validation du fichier (ton code existant)
        if (!inputFile.exists()) {
            log.error("Fichier CSV introuvable : {}", inputFile);
            contribution.setExitStatus(ExitStatus.FAILED);
            return RepeatStatus.FINISHED;
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputFile.getInputStream()))) {
            long count = reader.lines().count() - 1;
            if (count <= 0) {
                log.error("Le fichier CSV est vide");
                contribution.setExitStatus(ExitStatus.FAILED);
                return RepeatStatus.FINISHED;
            }
            log.info("Fichier validé : {} lignes de données", count);
        }

        return RepeatStatus.FINISHED;
    }
}