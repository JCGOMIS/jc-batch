package com.jcgomis.jcbatch.listener;


import com.jcgomis.jcbatch.dto.TransactionCsvDto;
import com.jcgomis.jcbatch.entity.Transaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.listener.SkipListener;
import org.springframework.stereotype.Component;

/**
 * Listener de skip : trace les éléments ignorés à chaque phase.
 *
 * En production, on pourrait écrire les lignes rejetées dans un fichier
 * d'erreur ou une table de rejet pour analyse ultérieure.
 */
@Slf4j
@Component
public class TransactionSkipListener implements SkipListener<TransactionCsvDto, Transaction> {

    @Override
    public void onSkipInRead(Throwable t) {
        log.error("Erreur lors de la lecture d'une ligne CSV : {}", t.getMessage());
    }

    @Override
    public void onSkipInProcess(TransactionCsvDto item, Throwable t) {
        log.error("Erreur lors du traitement de la transaction {} : {}",
                item.getReference(), t.getMessage());
    }

    @Override
    public void onSkipInWrite(Transaction item, Throwable t) {
        log.error("Erreur lors de l'écriture de la transaction {} : {}",
                item.getReference(), t.getMessage());
    }
}

