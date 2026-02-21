package com.jcgomis.jcbatch.processor;

import com.jcgomis.jcbatch.dto.TransactionCsvDto;
import com.jcgomis.jcbatch.entity.Transaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Processor de transformation : DTO CSV → Entity Transaction.
 *
 * Responsabilités :
 * 1. Validation métier (montant > 0, devise supportée, plafond)
 * 2. Conversion des types (String → BigDecimal, LocalDate, Enum)
 * 3. Enrichissement (statut, date de traitement)
 *
 * Retourner null = rejeter l'élément (il ne sera pas écrit).
 */
@Slf4j
@Component
public class TransactionProcessor implements ItemProcessor<TransactionCsvDto, Transaction> {

    private static final BigDecimal MONTANT_MAX = new BigDecimal("500000.00");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public Transaction process(TransactionCsvDto dto) throws Exception {

        log.debug("Traitement de la transaction : {}", dto.getReference());

        // ── 1. Validation métier ──────────────────────────────
        BigDecimal montant = new BigDecimal(dto.getMontant()).setScale(2, RoundingMode.HALF_UP);

        // Rejeter les montants négatifs ou nuls
        if (montant.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Transaction {} rejetée : montant invalide ({})", dto.getReference(), montant);
            return null; // Spring Batch ignorera cet élément
        }

        // Rejeter les montants dépassant le plafond
        if (montant.compareTo(MONTANT_MAX) > 0) {
            log.warn("Transaction {} rejetée : montant dépasse le plafond ({} > {})",
                    dto.getReference(), montant, MONTANT_MAX);
            return null;
        }

        // Vérifier la devise
        if (!"EUR".equalsIgnoreCase(dto.getDevise())) {
            log.warn("Transaction {} rejetée : devise non supportée ({})",
                    dto.getReference(), dto.getDevise());
            return null;
        }

        // ── 2. Conversion et mapping ─────────────────────────
        return Transaction.builder()
                .reference(dto.getReference())
                .montant(montant)
                .devise(dto.getDevise().toUpperCase())
                .dateTransaction(LocalDate.parse(dto.getDateTransaction(), DATE_FORMATTER))
                .compteSource(dto.getCompteSource())
                .compteDestination(dto.getCompteDestination())
                .type(Transaction.TypeTransaction.valueOf(dto.getType().toUpperCase()))
                .statut("VALIDEE")
                .dateTraitement(LocalDateTime.now())
                .build();
    }
}
