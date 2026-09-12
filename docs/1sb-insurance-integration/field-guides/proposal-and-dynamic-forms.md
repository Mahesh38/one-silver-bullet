# Proposal & dynamic forms

## Design intent (from 1SB)

> Insurance proposal forms vary for each company/product. Instead of multiple API signatures, 1SB exposes a **dynamic** GET form schema. Submit body mirrors that schema with user input values filled in.

**APIs (Term example):**

- `GET /insurance/lifeterm/v1/proposal` (params: `productId`, `manufacturerId`, `version`)
- `POST /insurance/lifeterm/v1/proposal`
- Proposal poll GET (product/manufacturer/request ids)

Health/Motor/Saving/etc. have parallel endpoints.

## Why fields look “all required” in schema dumps

The portal schema for Term proposal lists many required properties (KYC proofs, FATCA, NRI block, nominee, payout, ACR questions, etc.). In practice:

1. **Visibility / mandatory flags inside field metadata** drive runtime requirements.
2. Entire sections (e.g. NRI) apply only when `residentStatus` says so.
3. Different manufacturers return different fieldGroups.

**Implementation rule:** Build a **schema-driven renderer**, not a static Term proposal page.

## Recommended form engine behaviour

For each field node in GET response:

| Attribute (conceptual) | Use |
|------------------------|-----|
| name / description | Label / help |
| type / pattern | Widget + validation |
| mandatory | Client + server validation |
| visibility | Show/hide rules |
| order | Layout |
| options / enums | Dropdowns (refresh via Master Lookup when insurer-specific) |

On submit:

1. Clone GET schema structure.
2. Write user values into the designated input value slots (per 1SB doc: plug input value field tags).
3. POST entire structure.
4. Poll proposal response for UW decision / application numbers.

## Bank canonical approach

Do **not** promote every Term proposal field into the bank domain model.

Instead:

```text
ProposalSchema { schemaId, insurerId, productCode, version, pages[], rules[] }
ProposalDraft { schemaId, values: map<fieldPath, value>, parties[] }
ProposalSubmissionResult { applicationNo, status, outstandingRequirements[] }
```

Adapter converts `ProposalDraft` ↔ 1SB dynamic payload.

## Prefill strategy (bank CIF / RM)

Map what you already know into schema paths:

- Names, DOB, gender, PAN, mobile, email
- Address / pincode
- Occupation / income
- Nominee (if on file)
- Bank account (if penny-drop verified)

Leave medical / ACR / product-specific declarations to user/RM capture.

## Gate criteria forms

Same dynamic philosophy for eligibility (`gateCriteria` GET/POST) on Term/Saving.

Bank API (`FUNC-023`):

- `GET /v1/quotes/criteria?lob=&productCode=&manufacturerId=`
- `POST /v1/quotes/criteria` (requires `Idempotency-Key`)

Handlers call `{quoteSubmitPath}/gateCriteria?productId=&manufacturerId=`. Treat as a lighter proposal schema before quoting or before product lock-in (confirm sequencing with 1SB for each LOB).
