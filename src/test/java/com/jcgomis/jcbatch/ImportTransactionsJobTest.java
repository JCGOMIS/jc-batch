package com.jcgomis.jcbatch;



import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.TestPropertySource;

import java.util.Collection;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

/**
 * Test d'intégration du batch.
 *
 * @SpringBatchTest fournit automatiquement :
 * - JobLauncherTestUtils : lance le job ou un step isolé
 * - JobRepositoryTestUtils : nettoie les métadonnées entre les tests
 */
@SpringBatchTest
@SpringBootTest
@TestPropertySource(properties = {
        "batch.input-file=classpath:data/transactions.csv",
        "batch.chunk-size=10",
        "batch.skip-limit=5"
})
class ImportTransactionsJobTest {

    @Autowired
    private JobOperatorTestUtils jobOperatorTestUtils;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Job importTransactionsJob;

    @BeforeEach
    void cleanup(){
        jdbcTemplate.execute("DELETE FROM transactions");

        // Nettoyer les métadonnées Spring Batch (ordre important : FK)
        jdbcTemplate.execute("DELETE FROM BATCH_STEP_EXECUTION_CONTEXT");
        jdbcTemplate.execute("DELETE FROM BATCH_STEP_EXECUTION");
        jdbcTemplate.execute("DELETE FROM BATCH_JOB_EXECUTION_CONTEXT");
        jdbcTemplate.execute("DELETE FROM BATCH_JOB_EXECUTION_PARAMS");
        jdbcTemplate.execute("DELETE FROM BATCH_JOB_EXECUTION");
        jdbcTemplate.execute("DELETE FROM BATCH_JOB_INSTANCE");
    }

    @Test
    void shouldCompleteJobSuccessfully() throws Exception {
        // Given
        jobOperatorTestUtils.setJob(importTransactionsJob);
        JobParameters params = new JobParametersBuilder()
                .addLong("timestamp", System.currentTimeMillis())
                .toJobParameters();

        // When
        JobExecution jobExecution = jobOperatorTestUtils.startJob(params);

        // Then
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Collection<StepExecution> stepExecutions = jobExecution.getStepExecutions();
        assertThat(stepExecutions).asList().hasSize(1);

        StepExecution stepExecution = stepExecutions.iterator().next();

        // 10 lignes dans le CSV
        assertThat(stepExecution.getReadCount()).isEqualTo(10);

        // 3 filtrées (montant négatif, montant nul, devise USD) + 1 > plafond
        assertThat(stepExecution.getFilterCount()).isEqualTo(4);

        // 6 écrites en base
        assertThat(stepExecution.getWriteCount()).isEqualTo(6);

        // Aucune erreur skip
        assertThat(stepExecution.getSkipCount()).isZero();
    }

    @Test
    void shouldExecuteImportStepInIsolation() throws Exception {
        // Test d'un step isolé (utile pour tester chaque step indépendamment)
        jobOperatorTestUtils.setJob(importTransactionsJob);

        JobExecution jobExecution = jobOperatorTestUtils.startStep(
                "importTransactionsStep");

        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }
}