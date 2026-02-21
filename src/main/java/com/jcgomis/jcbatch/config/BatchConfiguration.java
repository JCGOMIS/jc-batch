package com.jcgomis.jcbatch.config;

import com.jcgomis.jcbatch.dto.TransactionCsvDto;
import com.jcgomis.jcbatch.entity.Transaction;
import com.jcgomis.jcbatch.listener.JobCompletionListener;
import com.jcgomis.jcbatch.listener.TransactionSkipListener;
import com.jcgomis.jcbatch.processor.TransactionProcessor;
import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.database.JpaItemWriter;
import org.springframework.batch.infrastructure.item.database.builder.JpaItemWriterBuilder;
import org.springframework.batch.infrastructure.item.file.FlatFileItemReader;
import org.springframework.batch.infrastructure.item.file.FlatFileParseException;
import org.springframework.batch.infrastructure.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.infrastructure.item.file.mapping.BeanWrapperFieldSetMapper;
import org.springframework.batch.infrastructure.repeat.policy.SimpleCompletionPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Configuration principale du batch Spring Batch.
 *
 * Architecture :
 * ┌────────────────────────────────────────────────────────────┐
 * │  importTransactionsJob                                      │
 * │  ┌────────────────────────────────────────────────────────┐ │
 * │  │  importStep (Chunk-Oriented, taille = 10)              │ │
 * │  │                                                        │ │
 * │  │  ┌──────────┐   ┌──────────────┐   ┌───────────────┐  │ │
 * │  │  │  Reader   │──▶│  Processor   │──▶│    Writer     │  │ │
 * │  │  │ (CSV)     │   │ (Validation) │   │ (JPA/BDD)     │  │ │
 * │  │  └──────────┘   └──────────────┘   └───────────────┘  │ │
 * │  │                                                        │ │
 * │  │  Fault Tolerance : skip(FlatFileParseException, 5)     │ │
 * │  └────────────────────────────────────────────────────────┘ │
 * │  Listeners : JobCompletionListener, TransactionSkipListener │
 * └────────────────────────────────────────────────────────────┘
 */
@Configuration
@RequiredArgsConstructor
public class BatchConfiguration {

    private final TransactionProcessor transactionProcessor;
    private final JobCompletionListener jobCompletionListener;
    private final TransactionSkipListener skipListener;
    private final EntityManagerFactory entityManagerFactory;

    @Value("${batch.input-file:classpath:data/transactions.csv}")
    private Resource inputFile;

    @Value("${batch.chunk-size:10}")
    private int chunkSize;

    @Value("${batch.skip-limit:5}")
    private int skipLimit;

    // ══════════════════════════════════════════════════════════
    //  READER : Lecture du fichier CSV
    // ══════════════════════════════════════════════════════════

    /**
     * FlatFileItemReader lit le fichier CSV ligne par ligne.
     *
     * - linesToSkip(1) : ignore la ligne d'en-tête
     * - delimited() : parsing par délimiteur (virgule par défaut)
     * - names() : mapping colonnes CSV → propriétés du DTO
     * - BeanWrapperFieldSetMapper : mapping automatique par nom de propriété
     *
     * Alternatives en production :
     * - JdbcCursorItemReader / JdbcPagingItemReader pour lire depuis une BDD
     * - JpaPagingItemReader pour utiliser JPA
     * - StaxEventItemReader pour du XML
     * - KafkaItemReader pour consommer depuis Kafka
     * - Custom ItemReader pour une API REST
     */
    @Bean
    public FlatFileItemReader<TransactionCsvDto> transactionReader() {
        return new FlatFileItemReaderBuilder<TransactionCsvDto>()
                .name("transactionReader")
                .resource(inputFile)
                .linesToSkip(1) // Skip header
                .delimited()
                .names("id", "reference", "montant", "devise",
                        "dateTransaction", "compteSource", "compteDestination", "type")
                .fieldSetMapper(new BeanWrapperFieldSetMapper<>() {{
                    setTargetType(TransactionCsvDto.class);
                }})
                .build();
    }

    // ══════════════════════════════════════════════════════════
    //  WRITER : Écriture en base via JPA
    // ══════════════════════════════════════════════════════════

    /**
     * JpaItemWriter persiste les entités Transaction en base.
     *
     * L'écriture se fait par chunk : toutes les entités du chunk
     * sont persistées dans une seule transaction.
     *
     * Alternatives :
     * - JdbcBatchItemWriter : écriture JDBC directe (plus performant)
     * - FlatFileItemWriter : écriture vers un fichier
     * - CompositeItemWriter : écriture vers plusieurs destinations
     * - KafkaItemWriter : publication vers Kafka
     */
    @Bean
    public JpaItemWriter<Transaction> transactionWriter() {
        return new JpaItemWriterBuilder<Transaction>()
                .entityManagerFactory(entityManagerFactory)
                .build();
    }

    // ══════════════════════════════════════════════════════════
    //  STEP : Définition du step chunk-oriented
    // ══════════════════════════════════════════════════════════

    /**
     * Step principal avec chunk processing.
     *
     * chunk(chunkSize) : nombre d'éléments lus avant un commit.
     *   - Petit chunk (10-50) : moins de mémoire, plus de commits
     *   - Grand chunk (100-1000) : plus rapide, mais plus de mémoire
     *     et rollback plus coûteux en cas d'erreur
     *
     * faultTolerant() active la tolérance aux erreurs :
     *   - skip() : ignore les éléments en erreur (jusqu'à skipLimit)
     *   - retry() : réessaye les erreurs transitoires (non utilisé ici)
     *   - noSkip() / noRetry() : exclut certaines exceptions
     */
    @Bean
    public Step importStep(JobRepository jobRepository,
                           PlatformTransactionManager transactionManager) {
        return new StepBuilder("importTransactionsStep", jobRepository)
                .<TransactionCsvDto, Transaction>chunk( chunkSize)
                .transactionManager(transactionManager)
                .reader(transactionReader())
                .processor(transactionProcessor)
                .writer(transactionWriter())
                // Tolérance aux erreurs
                .faultTolerant()
                .skip(FlatFileParseException.class)     // Ignorer les lignes CSV malformées
                .skip(NumberFormatException.class)        // Ignorer les montants non numériques
                .skipLimit(skipLimit)                     // Maximum 5 erreurs tolérées
                // Listeners
                .listener(skipListener)
                .build();
    }

    // ══════════════════════════════════════════════════════════
    //  JOB : Orchestration
    // ══════════════════════════════════════════════════════════

    /**
     * Job principal d'import des transactions.
     *
     * RunIdIncrementer : génère un identifiant unique à chaque exécution,
     * permettant de relancer le même job plusieurs fois.
     *
     * Pour un job multi-steps :
     *   .start(validationStep)
     *   .next(importStep)
     *   .next(reportStep)
     *
     * Pour un flow conditionnel :
     *   .start(importStep)
     *     .on("COMPLETED").to(reportStep)
     *     .from(importStep).on("FAILED").to(errorStep)
     *   .end()
     */
    @Bean
    public Job importTransactionsJob(JobRepository jobRepository, Step importStep) {
        return new JobBuilder("importTransactionsJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .listener(jobCompletionListener)
                .start(importStep)
                .build();
    }
}
