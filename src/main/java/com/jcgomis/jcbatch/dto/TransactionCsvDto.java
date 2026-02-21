package com.jcgomis.jcbatch.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionCsvDto {

    private Long id;
    private String reference;
    private String montant;       // String car provient du CSV brut
    private String devise;
    private String dateTransaction;
    private String compteSource;
    private String compteDestination;
    private String type;
}
