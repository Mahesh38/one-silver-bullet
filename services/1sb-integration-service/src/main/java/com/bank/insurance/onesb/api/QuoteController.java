package com.bank.insurance.onesb.api;

import com.bank.insurance.onesb.api.dto.CreateQuoteRequest;
import com.bank.insurance.onesb.api.dto.CreateQuoteResponse;
import com.bank.insurance.onesb.api.dto.EligibilitySubmitRequest;
import com.bank.insurance.onesb.api.dto.QuoteJobResponse;
import com.bank.insurance.onesb.domain.command.CreateQuoteCommand;
import com.bank.common.domain.EligibilitySubmitResult;
import com.bank.common.domain.JobStatus;
import com.bank.common.domain.Lob;
import com.bank.common.domain.ProposalSchema;
import com.bank.common.domain.QuoteJob;
import com.bank.common.domain.QuoteOffer;
import com.bank.insurance.onesb.domain.port.inbound.EligibilityUseCase;
import com.bank.insurance.onesb.domain.port.inbound.QuoteUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Bank quote API — {@code POST /v1/quotes} (FUNC-002), {@code GET /v1/quotes/{jobId}} (FUNC-003),
 * {@code GET/POST /v1/quotes/criteria} (FUNC-023).
 */
@RestController
@RequestMapping("/v1/quotes")
public class QuoteController {

    public static final String ACTOR_HEADER = "X-Actor-Id";
    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final QuoteUseCase quoteUseCase;
    private final EligibilityUseCase eligibilityUseCase;

    public QuoteController(QuoteUseCase quoteUseCase, EligibilityUseCase eligibilityUseCase) {
        this.quoteUseCase = quoteUseCase;
        this.eligibilityUseCase = eligibilityUseCase;
    }

    @PostMapping
    public ResponseEntity<CreateQuoteResponse> createQuote(
            @Valid @RequestBody CreateQuoteRequest request,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(value = ACTOR_HEADER, required = false) String actorId) {

        CreateQuoteCommand command = toCommand(request, idempotencyKey, actorId);
        String jobId = quoteUseCase.createQuote(command);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new CreateQuoteResponse(jobId, JobStatus.PENDING));
    }

    @GetMapping("/criteria")
    public ResponseEntity<ProposalSchema> getCriteria(
            @RequestParam Lob lob,
            @RequestParam String productCode,
            @RequestParam String manufacturerId) {
        return ResponseEntity.ok(eligibilityUseCase.getCriteria(lob, productCode, manufacturerId));
    }

    @PostMapping("/criteria")
    public ResponseEntity<EligibilitySubmitResult> submitCriteria(
            @Valid @RequestBody EligibilitySubmitRequest request,
            @RequestHeader(value = ACTOR_HEADER, required = false) String actorId) {
        return ResponseEntity.ok(eligibilityUseCase.submitCriteria(
                request.lob(),
                request.productCode(),
                request.manufacturerId(),
                request.values(),
                request.agentId() != null
                        ? request.agentId()
                        : (request.distribution() != null ? request.distribution().agentId() : null),
                request.distribution() != null ? request.distribution().channelType() : null,
                actorId));
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<QuoteJobResponse> getQuote(@PathVariable String jobId) {
        QuoteJob job = quoteUseCase.getQuoteResult(jobId);
        return ResponseEntity.ok(toResponse(job));
    }

    private static CreateQuoteCommand toCommand(CreateQuoteRequest request,
                                                String idempotencyKey,
                                                String actorId) {
        List<CreateQuoteCommand.MemberDetail> members = request.members().stream()
                .map(m -> new CreateQuoteCommand.MemberDetail(
                        m.role(),
                        m.sequenceNumber() != null ? m.sequenceNumber() : 0,
                        m.dob(),
                        m.gender(),
                        Boolean.TRUE.equals(m.tobacco()),
                        m.annualIncome(),
                        m.pincode()
                ))
                .toList();

        CreateQuoteCommand.DistributionContext distribution = null;
        if (request.distribution() != null) {
            distribution = new CreateQuoteCommand.DistributionContext(
                    request.distribution().rmEmployeeId(),
                    request.distribution().agentId(),
                    request.distribution().channelType()
            );
        }

        CreateQuoteCommand.ProductSelection selection = null;
        if (request.selection() != null) {
            selection = new CreateQuoteCommand.ProductSelection(
                    request.selection().insurerCode(),
                    request.selection().productCodes(),
                    request.selection().planOption(),
                    request.selection().coverOption(),
                    request.selection().deathBenefitOption(),
                    request.selection().policyTerm(),
                    request.selection().premiumPaymentTerm(),
                    request.selection().premiumFrequency(),
                    request.selection().premiumPaymentOption()
            );
        }

        return new CreateQuoteCommand(
                request.lob(),
                request.mode(),
                request.category(),
                request.sumAssured(),
                request.premiumAmount(),
                members,
                request.preferences(),
                distribution,
                request.journeyId(),
                request.sessionId(),
                idempotencyKey,
                StringUtils.hasText(actorId) ? actorId : "system",
                selection
        );
    }

    /**
     * Map domain job → response. PENDING/RUNNING/TIMEOUT/FAILED never fabricate offers;
     * COMPLETED/PARTIAL return stored offers (empty list if none).
     */
    static QuoteJobResponse toResponse(QuoteJob job) {
        List<QuoteOffer> offers = offersForStatus(job);
        return new QuoteJobResponse(job.jobId(), job.status(), job.failureReason(), offers);
    }

    private static List<QuoteOffer> offersForStatus(QuoteJob job) {
        JobStatus status = job.status();
        if (status == JobStatus.COMPLETED || status == JobStatus.PARTIAL) {
            return job.offers() != null ? List.copyOf(job.offers()) : List.of();
        }
        // PENDING, RUNNING, TIMEOUT, FAILED — never invent offers
        return List.of();
    }
}
