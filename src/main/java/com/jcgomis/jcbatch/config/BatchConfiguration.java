package com.jcgomis.jcbatch.config;

import com.jcgomis.jcbatch.dto.TransactionCsvDto;
import com.jcgomis.jcbatch.entity.Transaction;
import com.jcgomis.jcbatch.listener.JobCompletionListener;
import com.jcgomis.jcbatch.listener.TransactionSkipListener;
import com.jcgomis.jcbatch.partitioner.RangePartitioner;
import com.jcgomis.jcbatch.processor.TransactionProcessor;
import com.jcgomis.jcbatch.tasklet.ErrorNotificationTasklet;
import com.jcgomis.jcbatch.tasklet.FileValidationTasklet;
import com.jcgomis.jcbatch.tasklet.ReportTasklet;
import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.configuration.annotation.StepScope;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
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


    @Autowired
    private TransactionProcessor transactionProcessor;
    @Autowired
    private JobCompletionListener jobCompletionListener;
    @Autowired
    private TransactionSkipListener skipListener;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private FileValidationTasklet fileValidationTasklet;
    @Autowired
    private ErrorNotificationTasklet errorNotificationTasklet;
    @Autowired
    private ReportTasklet reportTasklet;
    @Autowired
    private RangePartitioner rangePartitioner;

    @Value("${batch.input-file:classpath:data/transactions.csv}")
    private Resource inputFile;

    @Value("${batch.chunk-size:10}")
    private int chunkSize;

    @Value("${batch.skip-limit:5}")
    private int skipLimit;

    @Value("${batch.partitioning.grid-size:4}")
    private int gridSize;

    @Value("${batch.partitioning.pool-size:4}")
    private int poolSize;

    // ══════════════════════════════════════════════════════════
    //  TASK EXECUTOR : Pool de threads pour le partitioning
    // ══════════════════════════════════════════════════════════

    @Bean
    public TaskExecutor batchTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(25);
        executor.setThreadNamePrefix("batch-worker-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    // ══════════════════════════════════════════════════════════
    //  READER : Lecture CSV (prototype scope pour le partitioning)
    // ══════════════════════════════════════════════════════════

    /**
     * Reader utilisé par chaque worker du partitioning.
     *
     * IMPORTANT : ne pas déclarer en @Bean singleton.
     * Chaque partition crée sa propre instance via cette méthode.
     *
     * Le maxItemCount et linesToSkip sont configurés par le
     * PartitionedStepBuilder via l'ExecutionContext de chaque partition.
     */
    @Bean
    @StepScope
    public FlatFileItemReader<TransactionCsvDto> partitionedReader(
            @Value("#{stepExecutionContext['linesToSkip']}") int linesToSkip,
            @Value("#{stepExecutionContext['maxItemCount']}")int maxItemCount){
        return new FlatFileItemReaderBuilder<TransactionCsvDto>()
                .name("partitionedTransactionReader")
                .resource(inputFile)
                .linesToSkip(linesToSkip)
                .maxItemCount(maxItemCount)
                .delimited()
                .names("id", "reference", "montant", "devise", "dateTransaction", "compteSource", "compteDestination","type")
                .fieldSetMapper(new BeanWrapperFieldSetMapper<>(){{
                    setTargetType(TransactionCsvDto.class);
                }})
                .build();
    }

    // ══════════════════════════════════════════════════════════
    //   Reader standard (non partitionné) — utilisé comme fallback  et pour les tests unitaires.
    // ══════════════════════════════════════════════════════════

    /**
     *
     *
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


    /**
     * Step 1 : Validation du fichier CSV.
     * Retourne COMPLETED ou FAILED → utilisé par le flow conditionnel.
     */


    @Bean
     public Step validationStep(JobRepository jobRepository, PlatformTransactionManager transactionManager){
        return new StepBuilder("validationStep", jobRepository)
                .tasklet(fileValidationTasklet,transactionManager)
                .build();
    }
    /**
     * Step worker : traitement d'une partition (chunk-oriented).
     * Chaque thread exécute ce step avec sa propre plage de lignes.
     */
    @Bean

    public Step workerStep(JobRepository jobRepository, PlatformTransactionManager platformTransactionManager){

        return new StepBuilder("workerStep", jobRepository)
                .<TransactionCsvDto, Transaction>chunk(chunkSize)
                .transactionManager(platformTransactionManager)
                .reader(transactionReader())
                .processor(transactionProcessor)
                .writer(transactionWriter())
                .faultTolerant()
                .skip(FlatFileParseException.class)
                .skip(NumberFormatException.class)
                .skipLimit(skipLimit)
                .listener(skipListener)
                .build();
    }
    /**
     * Step 2 : Import partitionné.
     *
     * Le Partitioner découpe le travail en N partitions.
     * Chaque partition est traitée par un thread séparé
     * exécutant le workerStep.
     *
     * gridSize = nombre de partitions (et donc de threads parallèles)
     */

    @Bean
    public Step partitionedImportStep(JobRepository jobRepository,
                                     @Qualifier("workerStep") Step workerStep) {
        return new StepBuilder("partitionedImportStep", jobRepository)
                .partitioner("workerStep", rangePartitioner)
                .step(workerStep)
                .gridSize(gridSize)
                .taskExecutor(batchTaskExecutor())
                .build();
    }
    /**
     * Step 3 : Génération du rapport (après import réussi).
     */
    @Bean
    public Step reportStep(JobRepository repository, PlatformTransactionManager platformTransactionManager){
        return new StepBuilder("reportStep", repository)
                .tasklet(reportTasklet, platformTransactionManager)
                .build();
    }
    /**
     * Step Erreur : notification en cas d'échec.
     */
    @Bean
    public Step errorNotificationStep(JobRepository jobRepository,
                                      PlatformTransactionManager transactionManager) {
        return new StepBuilder("errorNotificationStep", jobRepository)
                .tasklet(errorNotificationTasklet, transactionManager)
                .build();
    }

    // ══════════════════════════════════════════════════════════
    //  JOB : Flow conditionnel
    //

    /**
     * Job avec flow conditionnel :
     *
     * 1. validationStep
     *    → COMPLETED → partitionedImportStep
     *    → FAILED    → errorNotificationStep → FIN
     *
     * 2. partitionedImportStep
     *    → COMPLETED → reportStep → FIN
     *    → FAILED    → errorNotificationStep → FIN
     */
    @Bean
    public Job importTransactionsJob(JobRepository jobRepository,
                                   @Qualifier("validationStep")  Step validationStep,
                                     @Qualifier("partitionedImportStep") Step partitionedImportStep,
                                     @Qualifier("reportStep") Step reportStep,
                                     @Qualifier("errorNotificationStep")Step errorNotificationStep) {
        return new JobBuilder("importTransactionsJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .listener(jobCompletionListener)

                // Step 1 : Validation
                .start(validationStep)
                .on("COMPLETED").to(partitionedImportStep)    // Si OK → import
                .from(validationStep)
                .on("FAILED").to(errorNotificationStep)       // Si KO → alerte
                .on("FAILED").end()

                // Step 2 : Import partitionné
                .from(partitionedImportStep)
                .on("COMPLETED").to(reportStep)               // Si OK → rapport
                .from(partitionedImportStep)
                .on("FAILED").to(errorNotificationStep)       // Si KO → alerte
                .on("FAILED").end()

                // Step 3 : Rapport → fin
                .from(reportStep)
                .on("*").end()

                .end()
                .build();
    }
}
