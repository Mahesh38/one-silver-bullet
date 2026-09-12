package com.bank.insurance.onesb.domain.port.outbound;

import com.bank.common.domain.EligibilitySubmitResult;
import com.bank.common.domain.Lob;
import com.bank.common.domain.ProposalSchema;

/**
 * Outbound port for 1SB gateCriteria GET/POST.
 */
public interface OneSbEligibilityPort {

    ProposalSchema getCriteria(Lob lob, String productCode, String manufacturerId, String path);

    EligibilitySubmitResult submit(String path, Object payload);
}
