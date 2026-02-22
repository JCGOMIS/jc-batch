package com.jcgomis.jcbatch.tasklet;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Tasklet de reporting post-import.
 *
 * Génère un rapport statistique après un import réussi.
 * Utilise JdbcTemplate pour des requêtes d'agrégation directes
 * (plus performant que JPA pour du reporting).
 *
 * En production, ce rapport pourrait être :
 * - Écrit dans un fichier (CSV, PDF)
 * - Envoyé par email
 * - Stocké dans une table de reporting
 * - Publié vers un dashboard (Grafana, Kibana)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportTasklet implements Tasklet {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {

        log.info("Génération du rapport d'import...");

        // Récupérer le nombre de lignes du fichier (stocké par FileValidationTasklet)
        Long totalLines = chunkContext.getStepContext()
                .getStepExecution()
                .getJobExecution()
                .getExecutionContext()
                .getLong("totalLines", 0L);

        // Requêtes d'agrégation
        Long totalTransactions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE statut = 'VALIDEE'", Long.class);

        BigDecimal montantTotal = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(montant), 0) FROM transactions WHERE statut = 'VALIDEE'",
                BigDecimal.class);

        BigDecimal montantMoyen = jdbcTemplate.queryForObject(
                "SELECT COALESCE(AVG(montant), 0) FROM transactions WHERE statut = 'VALIDEE'",
                BigDecimal.class);

        Long virements = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE type = 'VIREMENT' AND statut = 'VALIDEE'",
                Long.class);

        Long prelevements = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE type = 'PRELEVEMENT' AND statut = 'VALIDEE'",
                Long.class);

        long rejetees = totalLines - (totalTransactions != null ? totalTransactions : 0);

        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"));

        log.info("╔══════════════════════════════════════════════════╗");
        log.info("║           RAPPORT D'IMPORT — {}      ║", now);
        log.info("╠══════════════════════════════════════════════════╣");
        log.info("║  Lignes dans le fichier : {}", String.format("%-25d║", totalLines));
        log.info("║  Transactions importées : {}", String.format("%-25d║", totalTransactions));
        log.info("║  Transactions rejetées  : {}", String.format("%-25d║", rejetees));
        log.info("╠──────────────────────────────────────────────────╣");
        log.info("║  Montant total          : {} EUR", String.format("%-18s║", montantTotal));
        log.info("║  Montant moyen          : {} EUR", String.format("%-18s║", montantMoyen));
        log.info("╠──────────────────────────────────────────────────╣");
        log.info("║  Virements              : {}", String.format("%-25d║", virements));
        log.info("║  Prélèvements           : {}", String.format("%-25d║", prelevements));
        log.info("╚══════════════════════════════════════════════════╝");

        return RepeatStatus.FINISHED;
    }
}

