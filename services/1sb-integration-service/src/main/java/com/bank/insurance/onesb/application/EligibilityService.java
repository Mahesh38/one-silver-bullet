package com.bank.insurance.onesb.application;

import com.bank.common.domain.EligibilitySubmitResult;
import com.bank.common.domain.Lob;
import com.bank.common.domain.ProposalSchema;
import com.bank.common.error.ErrorCodes;
import com.bank.common.error.ServiceError;
import com.bank.common.error.ServiceErrors;
import com.bank.common.secrets.SecretProvider;
import com.bank.insurance.onesb.domain.port.inbound.EligibilityUseCase;
import com.bank.insurance.onesb.domain.port.outbound.OneSbEligibilityPort;
import com.bank.insurance.onesb.lob.LobQuoteHandler;
import com.bank.insurance.onesb.lob.LobQuoteHandlerRegistry;
import com.bank.insurance.onesb.lob.life.payload.LifeGateCriteriaSubmitBody;
import com.bank.insurance.onesb.lob.life.payload.LifeQuoteRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * Life eligibility (gateCriteria) orchestration — FUNC-023.
 */
@Service
public class EligibilityService implements EligibilityUseCase {

    private final LobQuoteHandlerRegistry handlerRegistry;
    private final OneSbEligibilityPort eligibilityPort;
    private final SecretProvider secretProvider;
    private final ServiceErrors serviceErrors;

    public EligibilityService(
            LobQuoteHandlerRegistry handlerRegistry,
            OneSbEligibilityPort eligibilityPort,
            SecretProvider secretProvider,
            ServiceErrors serviceErrors) {
        this.handlerRegistry = handlerRegistry;
        this.eligibilityPort = eligibilityPort;
        this.secretProvider = secretProvider;
        this.serviceErrors = serviceErrors;
    }

    @Override
    public ProposalSchema getCriteria(Lob lob, String productCode, String manufacturerId) {
        String path = requirePath(lob, productCode, manufacturerId, "getCriteria");
        return eligibilityPort.getCriteria(lob, productCode, manufacturerId, path);
    }

    @Override
    public EligibilitySubmitResult submitCriteria(
            Lob lob,
            String productCode,
            String manufacturerId,
            Map<String, Object> values,
            String agentId,
            String channelType,
            String actorId) {
        String path = requirePath(lob, productCode, manufacturerId, "submitCriteria");
        LifeQuoteRequest.Distributor distributor = new LifeQuoteRequest.Distributor(
                secretProvider.getDistributorId(),
                StringUtils.hasText(agentId) ? agentId.trim() : "",
                StringUtils.hasText(channelType) ? channelType.trim() : "B2B",
                "Online"
        );
        LifeGateCriteriaSubmitBody body = new LifeGateCriteriaSubmitBody(distributor, values);
        return eligibilityPort.submit(path, body);
    }

    private String requirePath(Lob lob, String productCode, String manufacturerId, String operation) {
        if (lob == null) {
            throw serviceErrors.error(ErrorCodes.VALIDATION_ERROR)
                    .component("EligibilityService")
                    .operation(operation)
                    .reason("lob is required")
                    .errors(List.of(ServiceError.ofField(
                            ErrorCodes.MISSING_REQUIRED_FIELD, "lob is required", "lob")))
                    .build();
        }
        if (!StringUtils.hasText(productCode) || !StringUtils.hasText(manufacturerId)) {
            throw serviceErrors.error(ErrorCodes.VALIDATION_ERROR)
                    .component("EligibilityService")
                    .operation(operation)
                    .reason("productCode and manufacturerId are required")
                    .errors(List.of(
                            ServiceError.ofField(ErrorCodes.MISSING_REQUIRED_FIELD,
                                    "productCode is required", "productCode"),
                            ServiceError.ofField(ErrorCodes.MISSING_REQUIRED_FIELD,
                                    "manufacturerId is required", "manufacturerId")))
                    .build();
        }
        LobQuoteHandler handler = handlerRegistry.get(lob);
        String path = handler.criteriaPath(productCode, manufacturerId);
        if (!StringUtils.hasText(path)) {
            throw serviceErrors.error(ErrorCodes.UNSUPPORTED_LOB)
                    .component("EligibilityService")
                    .operation(operation)
                    .reason("gateCriteria is not supported for lob " + lob)
                    .errors(List.of(ServiceError.ofField(
                            ErrorCodes.UNSUPPORTED_LOB,
                            "gateCriteria is not supported for lob " + lob,
                            "lob")))
                    .build();
        }
        return path;
    }
}
