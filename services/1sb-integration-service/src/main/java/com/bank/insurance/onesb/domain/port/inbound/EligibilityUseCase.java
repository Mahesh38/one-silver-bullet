package com.bank.insurance.onesb.domain.port.inbound;

import com.bank.common.domain.EligibilitySubmitResult;
import com.bank.common.domain.Lob;
import com.bank.common.domain.ProposalSchema;

import java.util.Map;

/**
 * Product eligibility (1SB gateCriteria) for Life LOBs.
 */
public interface EligibilityUseCase {

    ProposalSchema getCriteria(Lob lob, String productCode, String manufacturerId);

    EligibilitySubmitResult submitCriteria(
            Lob lob,
            String productCode,
            String manufacturerId,
            Map<String, Object> values,
            String agentId,
            String channelType,
            String actorId);
}
