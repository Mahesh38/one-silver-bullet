package com.bank.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "integration_job_offer")
@Getter
@Setter
@NoArgsConstructor
public class IntegrationJobOfferEntity {

    @Id
    @Column(name = "offer_id", length = 36, nullable = false)
    private String offerId;

    @Column(name = "job_id", length = 36, nullable = false)
    private String jobId;

    @Column(name = "insurer_code", length = 50)
    private String insurerCode;

    @Column(name = "product_code", length = 100)
    private String productCode;

    @Column(name = "product_name", length = 200)
    private String productName;

    @Column(name = "premium_amount", precision = 15, scale = 2)
    private BigDecimal premiumAmount;

    @Column(name = "premium_frequency", length = 10)
    private String premiumFrequency;

    @Column(name = "sum_assured", precision = 15, scale = 2)
    private BigDecimal sumAssured;

    @Column(name = "out_of_bound")
    private Boolean outOfBound = false;

    @Column(name = "offer_status", length = 20)
    private String offerStatus;

    @Column(name = "error_summary", length = 500)
    private String errorSummary;

    @Column(name = "raw_offer_blob_id", length = 36)
    private String rawOfferBlobId;

    /** JSON array of bank {@code FundAllocation} rows; null when the offer has no funds. */
    @Column(name = "funds_json")
    private String fundsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
