package com.bank.insurance.onesb.application;

import com.bank.common.domain.Lob;
import com.bank.common.domain.ProposalSchema;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("FUNC-025")
class ProposalFormValidatorTest {

    @Test
    void missingMandatory_emptySchema_returnsEmpty() {
        assertThat(ProposalFormValidator.missingMandatory(null, Map.of())).isEmpty();
        assertThat(ProposalFormValidator.missingMandatory(
                new ProposalSchema(Lob.TERM, "T1", "HDFC", "1", Map.of()),
                Map.of())).isEmpty();
    }

    @Test
    void missingMandatory_listsUnsetRequiredFields_andCapsAt20() {
        ProposalSchema schema = new ProposalSchema(Lob.TERM, "T1", "HDFC", "1", Map.of(
                "fieldGroups", List.of(
                        Map.of("fields", List.of(
                                Map.of("id", "proposer.panNumber", "mandatory", true),
                                Map.of("name", "nominee.name", "required", "Yes"),
                                Map.of("id", "optional.field", "mandatory", false)
                        ))
                )
        ));

        List<String> missing = ProposalFormValidator.missingMandatory(
                schema, Map.of("proposer.panNumber", "ABCDE1234F"));

        assertThat(missing).containsExactly("nominee.name");
        assertThat(ProposalFormValidator.missingMandatory(
                schema, Map.of("proposer.panNumber", "ABCDE1234F", "nominee.name", "Jane")))
                .isEmpty();
    }

    @Test
    void missingMandatory_treatsBlankStringAsAbsent() {
        ProposalSchema schema = new ProposalSchema(Lob.TERM, "T1", "HDFC", "1", Map.of(
                "fields", List.of(Map.of("id", "proposer.panNumber", "mandatory", true))
        ));
        assertThat(ProposalFormValidator.missingMandatory(
                schema, Map.of("proposer.panNumber", "  ")))
                .containsExactly("proposer.panNumber");
    }
}
