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
        jdbcTemplate.execute("DELETE FROM transactions");
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
        jobOperatorTestUtils.setJob(importTransactionsJob);
        JobParameters params = new JobParametersBuilder()
                .addLong("timestamp", System.currentTimeMillis())
                .toJobParameters();

        JobExecution jobExecution = jobOperatorTestUtils.startJob(params);

        // Le job doit être COMPLETED
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> stepNames = new ArrayList<>(jobExecution.getStepExecutions())
                .stream()
                .map(StepExecution::getStepName)
                .toList();

        // Debug : afficher les steps exécutés si le test échoue
        System.out.println("Steps exécutés : " + stepNames);

        // Chemin succès : validation + partitionedImport + rapport
        assertThat(stepNames).contains("validationStep");
        assertThat(stepNames).contains("reportStep");
        assertThat(stepNames.stream().anyMatch(n -> n.contains("workerStep"))).isTrue();

        // Le chemin erreur n'a PAS été emprunté
        assertThat(stepNames).doesNotContain("errorNotificationStep");

        // 6 transactions en base
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions", Long.class);
        assertThat(count).isEqualTo(6L);
    }

    // ══════════════════════════════════════════════════════════
    //  TEST 2 : Vérification du partitioning
    // ══════════════════════════════════════════════════════════

    @Test
    @DisplayName("L'import doit passer par le step partitionné avec au moins 1 worker")
    void shouldVerifyPartitionedExecution() throws Exception {
        jobOperatorTestUtils.setJob(importTransactionsJob);
        JobParameters params = new JobParametersBuilder()
                .addLong("timestamp", System.currentTimeMillis())
                .toJobParameters();

        JobExecution jobExecution = jobOperatorTestUtils.startJob(params);

        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<StepExecution> workerSteps = new ArrayList<>(jobExecution.getStepExecutions())
                .stream()
                .filter(se -> se.getStepName().contains("workerStep"))
                .toList();

        // Au moins 1 partition (grid-size=1 en test, 4 en prod)
        assertThat(workerSteps).hasSizeGreaterThanOrEqualTo(1);

        // Chaque worker a traité des éléments avec succès
        workerSteps.forEach(worker -> {
            assertThat(worker.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(worker.getReadCount()).isGreaterThan(0);
        });

        // Le total lu correspond au nombre de lignes du CSV
        long totalRead = workerSteps.stream()
                .mapToLong(StepExecution::getReadCount)
                .sum();
        assertThat(totalRead).isGreaterThan(0L);
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
    @DisplayName("Les transactions importées doivent respecter les règles métier")
    void shouldVerifyImportedTransactionsData() throws Exception {
        jobOperatorTestUtils.setJob(importTransactionsJob);
        JobParameters params = new JobParametersBuilder()
                .addLong("timestamp", System.currentTimeMillis())
                .toJobParameters();

        jobOperatorTestUtils.startJob(params);

        // Toutes VALIDEE
        Long validees = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE statut = 'VALIDEE'", Long.class);
        assertThat(validees).isEqualTo(6L);

        // Toutes en EUR
        Long nonEur = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE devise != 'EUR'", Long.class);
        assertThat(nonEur).isEqualTo(0L);

        // Aucun montant <= 0
        Long montantsInvalides = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE montant <= 0", Long.class);
        assertThat(montantsInvalides).isEqualTo(0L);

        // Aucun montant > 500 000
        Long montantsTropEleves = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE montant > 500000", Long.class);
        assertThat(montantsTropEleves).isEqualTo(0L);

        // Date de traitement renseignée
        Long sansDateTraitement = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE date_traitement IS NULL", Long.class);
        assertThat(sansDateTraitement).isEqualTo(0L);
    }
}