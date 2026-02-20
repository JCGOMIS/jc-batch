# Architecture Spring Batch — Guide Complet

## 1. Vue d'ensemble de l'architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         JobLauncher                                  │
│                   (déclenche l'exécution)                            │
│          Scheduler / API REST / CommandLineRunner                    │
└───────────────────────────┬─────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│                             Job                                      │
│                   (unité d'exécution principale)                     │
│                                                                      │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────────┐  │
│  │  Step 1   │───▶│  Step 2   │───▶│  Step 3   │───▶│  Step N ...  │  │
│  │(Validation│    │ (Import)  │    │(Reporting)│    │             │  │
│  │ Tasklet)  │    │ (Chunk)   │    │ (Chunk)   │    │             │  │
│  └──────────┘    └──────────┘    └──────────┘    └──────────────┘  │
│                                                                      │
│  Listeners: JobExecutionListener                                     │
└───────────────────────────┬─────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        JobRepository                                 │
│              (métadonnées dans BATCH_* tables)                       │
│                                                                      │
│  BATCH_JOB_INSTANCE  │  BATCH_JOB_EXECUTION  │  BATCH_STEP_EXECUTION│
│  BATCH_JOB_EXECUTION_PARAMS  │  BATCH_JOB_EXECUTION_CONTEXT         │
└─────────────────────────────────────────────────────────────────────┘
```

## 2. Modèle Chunk-Oriented Processing

Le cœur de Spring Batch. Chaque chunk est une transaction :

```
                    ┌─── Transaction Begin ───┐
                    │                          │
  ┌─────────┐      │  ┌───────────────────┐   │      ┌──────────┐
  │  Source  │──────┼─▶│  Read N items     │   │      │          │
  │ (CSV,    │      │  │  (chunk size = N) │   │      │  Target  │
  │  BDD,    │      │  └────────┬──────────┘   │      │ (BDD,    │
  │  Kafka)  │      │           │              │      │  File,   │
  └─────────┘      │  ┌────────▼──────────┐   │      │  Kafka)  │
                    │  │  Process each     │   │      │          │
                    │  │  item (validate,  │   │      └────▲─────┘
                    │  │  transform)       │   │           │
                    │  └────────┬──────────┘   │           │
                    │           │              │           │
                    │  ┌────────▼──────────┐   │           │
                    │  │  Write N items    │───┼───────────┘
                    │  │  (batch insert)   │   │
                    │  └───────────────────┘   │
                    │                          │
                    └─── Transaction Commit ───┘
                              │
                    Recommence jusqu'à épuisement
                    du Reader (retourne null)
```

**Pourquoi le chunk processing ?**
- Mémoire contrôlée : seuls N éléments en mémoire à la fois
- Performance : batch insert au lieu d'insert unitaire
- Reprise sur erreur : redémarrage au dernier chunk committé
- Transaction scope : rollback limité à un chunk

## 3. Composants Principaux

### 3.1 ItemReader — Implémentations courantes

| Reader                    | Source          | Pagination | Cas d'usage                  |
|---------------------------|-----------------|------------|------------------------------|
| `FlatFileItemReader`      | CSV, TXT        | —          | Import fichier plat          |
| `StaxEventItemReader`     | XML             | —          | Import XML                   |
| `JsonItemReader`          | JSON            | —          | Import JSON                  |
| `JdbcCursorItemReader`    | JDBC (curseur)  | Non        | Lecture séquentielle BDD     |
| `JdbcPagingItemReader`    | JDBC (pages)    | Oui        | Gros volumes BDD             |
| `JpaPagingItemReader`     | JPA             | Oui        | Lecture BDD via JPA          |
| `KafkaItemReader`         | Kafka           | —          | Consommation de messages     |
| Custom `ItemReader`       | API REST, etc.  | —          | Sources non standard         |

### 3.2 ItemProcessor

- Transformation de type (DTO → Entity)
- Validation métier
- Enrichissement (appels à d'autres services)
- Filtrage (retourner `null` = ignorer l'élément)
- **CompositeItemProcessor** pour chaîner plusieurs processors

### 3.3 ItemWriter — Implémentations courantes

| Writer                    | Cible           | Cas d'usage                       |
|---------------------------|-----------------|-----------------------------------|
| `JpaItemWriter`           | JPA/Hibernate   | Persistance ORM                   |
| `JdbcBatchItemWriter`     | JDBC direct     | Haute performance (recommandé)    |
| `FlatFileItemWriter`      | CSV, TXT        | Export fichier                    |
| `JsonFileItemWriter`      | JSON            | Export JSON                       |
| `KafkaItemWriter`         | Kafka           | Publication messages              |
| `CompositeItemWriter`     | Multiple        | Écriture multi-destinations       |

#

## 6. Bonnes Pratiques en Production

1. **Idempotence** : un job relancé avec les mêmes paramètres doit produire le même résultat
2. **Monitoring** : exposer les métriques via Micrometer/Actuator (`/actuator/batch`)
3. **Chunk size** : commencer à 100, ajuster selon les benchmarks
4. **JobParameters** : utiliser des paramètres significatifs (date du fichier, nom) plutôt qu'un timestamp
5. **Externaliser la config** : chunk size, skip limit, chemins dans `application.yml`
6. **Tests** : `@SpringBatchTest` + `JobLauncherTestUtils` pour tester chaque step isolément
7. **Scheduling** : utiliser Spring Scheduler, Kubernetes CronJob ou un ordonnanceur (Control-M, Autosys)
8. **Séparation DTO/Entity** : ne jamais mapper le CSV directement sur l'entité JPA
