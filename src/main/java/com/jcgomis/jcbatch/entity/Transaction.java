package com.jcgomis.jcbatch.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Entité JPA représentant une transaction bancaire validée et transformée.
 */
@Entity
@Table(name = "transactions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String reference;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal montant;

    @Column(nullable = false, length = 3)
    private String devise;

    @Column(nullable = false)
    private LocalDate dateTransaction;

    @Column(nullable = false, length = 34)
    private String compteSource;

    @Column(nullable = false, length = 34)
    private String compteDestination;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TypeTransaction type;

    @Column(nullable = false)
    private String statut;

    @Column(nullable = false)
    private LocalDateTime dateTraitement;

    public enum TypeTransaction {
        VIREMENT, PRELEVEMENT, CARTE, CHEQUE
    }
}