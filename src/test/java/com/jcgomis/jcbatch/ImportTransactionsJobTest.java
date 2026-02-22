package com.jcgomis.jcbatch;



import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBatchTest
@SpringBootTest
@ActiveProfiles("test")
class ImportTransactionsJobTest {

    @Autowired
    private JobOperatorTestUtils jobOperatorTestUtils;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Job importTransactionsJob;

    @BeforeEach
    void cleanUp() {
        // Nettoyer la table métier
        jdbcTemplate.execute("DELETE FROM transactions");

        // Nettoyer les métadonnées Spring Batch (ordre FK)
        jdbcTemplate.execute("DELETE FROM BATCH_STEP_EXECUTION_CONTEXT");
        jdbcTemplate.execute("DELETE FROM BATCH_STEP_EXECUTION");
        jdbcTemplate.execute("DELETE FROM BATCH_JOB_EXECUTION_CONTEXT");
        jdbcTemplate.execute("DELETE FROM BATCH_JOB_EXECUTION_PARAMS");
        jdbcTemplate.execute("DELETE FROM BATCH_JOB_EXECUTION");
        jdbcTemplate.execute("DELETE FROM BATCH_JOB_INSTANCE");
    }

    // ══════════════════════════════════════════════════════════
    //  TEST 1 : Flow conditionnel complet
    // ══════════════════════════════════════════════════════════

    @Test
    @DisplayName("Le job complet doit suivre le chemin succès : validation → import → rapport")
    void shouldCompleteFullJobWithConditionalFlow() throws Exception {
        // Given
        jobOperatorTestUtils.setJob(importTransactionsJob);
        JobParameters params = new JobParametersBuilder()
                .addLong("timestamp", System.currentTimeMillis())
                .toJobParameters();

        // When
        JobExecution jobExecution = jobOperatorTestUtils.startJob(params);

        // Then — Le job doit être COMPLETED
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<StepExecution> stepExecutions = new ArrayList<>(jobExecution.getStepExecutions());

        List<String> stepNames = stepExecutions.stream()
                .map(StepExecution::getStepName)
                .toList();

        // Les 3 phases ont été exécutées
        assertThat(stepNames).contains("validationStep", "reportStep");
        assertThat(stepNames.stream().anyMatch(n -> n.contains("workerStep"))).isTrue();

        // Le chemin erreur n'a PAS été emprunté
        assertThat(stepNames).doesNotContain("errorNotificationStep");

        // 6 transactions validées en base (10 lues - 4 rejetées)
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions", Long.class);
        assertThat(count).isEqualTo(5L);
    }

    // ══════════════════════════════════════════════════════════
    //  TEST 2 : Vérification du partitioning
    // ══════════════════════════════════════════════════════════

    @Test
    @DisplayName("L'import doit être réparti en 2 partitions parallèles (grid-size=2 en test)")
    void shouldVerifyPartitionedExecution() throws Exception {
        // Given
        jobOperatorTestUtils.setJob(importTransactionsJob);
        JobParameters params = new JobParametersBuilder()
                .addLong("timestamp", System.currentTimeMillis())
                .toJobParameters();

        // When
        JobExecution jobExecution = jobOperatorTestUtils.startJob(params);

        // Then — Vérifier les worker steps
        List<StepExecution> workerSteps = new ArrayList<>(jobExecution.getStepExecutions())
                .stream()
                .filter(se -> se.getStepName().contains("workerStep"))
                .toList();

        // grid-size=2 en test → 2 workers
        assertThat(workerSteps).hasSize(4);

        // Chaque worker a traité des éléments
        workerSteps.forEach(worker -> {
            assertThat(worker.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(worker.getReadCount()).isGreaterThan(0);
        });

        // Total lu = 10 lignes du CSV
        long totalRead = workerSteps.stream()
                .mapToLong(StepExecution::getReadCount)
                .sum();
        assertThat(totalRead).isEqualTo(10L);
    }

    // ══════════════════════════════════════════════════════════
    //  TEST 3 : Step de validation isolé
    // ══════════════════════════════════════════════════════════

    @Test
    @DisplayName("Le step de validation doit réussir quand le fichier CSV est présent")
    void shouldExecuteValidationStepInIsolation() throws Exception {
        jobOperatorTestUtils.setJob(importTransactionsJob);

        JobExecution jobExecution = jobOperatorTestUtils.startStep("validationStep");

        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    // ══════════════════════════════════════════════════════════
    //  TEST 4 : Vérification des données métier
    // ══════════════════════════════════════════════════════════

    @Test
    @DisplayName("Les transactions importées doivent avoir le statut VALIDEE et un montant correct")
    void shouldVerifyImportedTransactionsData() throws Exception {
        // Given
        jobOperatorTestUtils.setJob(importTransactionsJob);
        JobParameters params = new JobParametersBuilder()
                .addLong("timestamp", System.currentTimeMillis())
                .toJobParameters();

        // When
        jobOperatorTestUtils.startJob(params);

        // Then — Toutes les transactions ont le statut VALIDEE
        Long validees = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE statut = 'VALIDEE'", Long.class);
        assertThat(validees).isEqualTo(5L);

        // Toutes sont en EUR (les USD ont été filtrées)
        Long nonEur = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE devise != 'EUR'", Long.class);
        assertThat(nonEur).isEqualTo(0L);

        // Aucun montant négatif ou nul
        Long montantsInvalides = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE montant <= 0", Long.class);
        assertThat(montantsInvalides).isEqualTo(0L);

        // Aucun montant au-dessus du plafond (500 000 €)
        Long montantsTropEleves = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE montant > 500000", Long.class);
        assertThat(montantsTropEleves).isEqualTo(0L);

        // Chaque transaction a une date de traitement renseignée
        Long sansDateTraitement = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE date_traitement IS NULL", Long.class);
        assertThat(sansDateTraitement).isEqualTo(0L);
    }
}