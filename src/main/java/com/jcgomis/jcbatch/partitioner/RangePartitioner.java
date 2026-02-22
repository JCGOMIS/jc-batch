package com.jcgomis.jcbatch.partitioner;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * Partitioner qui découpe le fichier CSV en plages de lignes.
 *
 * Fonctionnement :
 * 1. Compte le nombre total de lignes dans le fichier
 * 2. Divise en N partitions égales (gridSize)
 * 3. Chaque partition reçoit un startLine et endLine dans son ExecutionContext
 *
 * Exemple avec 100 lignes et gridSize=4 :
 *   - Partition 0 : lignes 1-25
 *   - Partition 1 : lignes 26-50
 *   - Partition 2 : lignes 51-75
 *   - Partition 3 : lignes 76-100
 *
 * Le worker reader (@StepScope) lit ces valeurs pour configurer
 * son linesToSkip et maxItemCount.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RangePartitioner implements Partitioner {

    @Value("${batch.input-file:classpath:data/transactions.csv}")
    private Resource inputFile;

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {

        Map<String, ExecutionContext> partitions = new HashMap<>();

        int totalLines = countDataLines();
        int linesPerPartition = (int) Math.ceil((double) totalLines / gridSize);

        log.info("Partitioning : {} lignes de données réparties en {} partitions ({} lignes/partition)",
                totalLines, gridSize, linesPerPartition);

        for (int i = 0; i < gridSize; i++) {
            ExecutionContext context = new ExecutionContext();

            int startLine = i * linesPerPartition + 1;  // +1 car l'en-tête est la ligne 0
            int endLine = Math.min((i + 1) * linesPerPartition, totalLines);

            // Nombre de lignes à sauter (en-tête + lignes des partitions précédentes)
            context.putInt("startLine", startLine);
            context.putInt("endLine", endLine);
            context.putInt("linesToSkip", startLine); // en-tête (1) + lignes précédentes
            context.putInt("maxItemCount", endLine - startLine + 1);
            context.putString("partitionName", "partition-" + i);

            partitions.put("partition-" + i, context);

            log.info("  → partition-{} : lignes {} à {} ({} éléments)",
                    i, startLine, endLine, endLine - startLine + 1);
        }

        return partitions;
    }

    /**
     * Compte le nombre de lignes de données (hors en-tête).
     */
    private int countDataLines() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputFile.getInputStream()))) {
            // -1 pour exclure l'en-tête
            return (int) reader.lines().count() - 1;
        } catch (Exception e) {
            log.error("Impossible de compter les lignes du fichier : {}", e.getMessage());
            throw new RuntimeException("Erreur lecture fichier pour partitioning", e);
        }
    }
}
