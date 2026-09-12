# Term quote — mandatory fields, when & why

**API:** `POST /insurance/lifeterm/v1/quote`  
**Poll:** `GET /insurance/lifeterm/v1/quote/poll/:requestId`  
**Portal:** [Get quote (Term)](https://docs.1silverbullet.tech/docs/insurance/retail/apiDocs/consumer-request-insurance-v-1-consumer-insurance-post)

---

## Top-level controls

| Field | Required | When | Why |
|-------|----------|------|-----|
| `typeOfQuote` | Yes | Always | `Single Quote` targets one insurer/product; `Multi-Quote` fans out across eligible products |
| `quoteCategory` | Yes | Always | Drives input meaning: `Premium`, `Sum Assured`, or `Income` |
| `includeBI` | No | When BI needed | `withBI` / `withoutBI` / `OnlyBI` — controls whether manufacturer BI is triggered |
| `alternateFreqRequired` | No | Comparison UX | `Yes` also quotes alternate frequency (Monthly↔Yearly rules) |
| `outOfBoundConfig` | No | Soft eligibility | `Yes` auto-adjusts illegal inputs to nearest allowed and flags OOB; `No` drops product |

### quoteCategory ↔ money fields

| quoteCategory | Put amount in | Do not |
|---------------|---------------|--------|
| `Sum Assured` | `quoteAmount` (= SA) | — |
| `Premium` | `quoteAmount` (= premium) | — |
| `Income` | `DBPoption.incomeAmount` (etc.) | Do **not** populate `quoteAmount` |

---

## `additionalSetup` (recommended for bank)

| Field | Required | Why |
|-------|----------|-----|
| `currency` | Strongly recommended | Policy currency (`INR` for India) |
| `userCountry` / `userRegion` / `userLanguage` | Optional | Context / compliance telemetry |
| `userIP` / `userAgent` / geo | Optional | Fraud / audit context |

---

## `distributor` (required)

| Field | Required | Why |
|-------|----------|-----|
| `distributorID` | Yes | 1SB tenant/distributor identity; used with auth + IP allowlist |
| `agentID` | Yes | Insurer SP/PoSP/BQP code — **critical for RM-assisted** |
| `channelType` | Yes | `B2B` for RM-assisted bank; `B2C` for self-serve |
| `salesChannel` | Optional | `Online` / `Others` |
| `agentType` | Optional | `POSP` / `SP` / `BQP` |
| `varFields[]` | Optional | Distributor-specific extras |

**Bank mapping:** RM employee id stays internal; map to insurer `agentID` via AgentPort / SP data.

---

## `personalInformation.individualDetails[]` (required)

At least one member for Term (typically Life Assured; proposer when different).

| Field | Required | When | Why |
|-------|----------|------|-----|
| `memberType` | Yes | Always | Role; Term docs list `Life Assured` (proposer used when distinct) |
| `memberSequenceNumber` | Yes | Always | Stable member key inside quote |
| `gender` | Yes | Always | Rating / eligibility |
| `dateOfBirth` | Yes | Always | Age calculation (`YYYY-MM-DD`) |
| `annualIncome` | Yes | Always | Financial UW / eligibility |
| `tobacco` | Yes | Always | Term risk class (`Yes`/`No`) |
| `zipCode` | Yes | Always | Location / product availability |
| `title` / names / email / mobile | Conditionally | Often for Single Quote / later proposal | Prefill now to reduce drop-off |
| `relationWithFirstLifeAssured` | Conditionally | **Mandatory for Single Quote** when proposer ≠ structure needs it | Relationship integrity |
| `occupation` / `qualification` / `maritalStatus` / `residentStatus` | Optional at quote | Often mandatory later at proposal | Prefer capture early if known from CIF |
| `riderDetails[]` | When riders chosen | Individual rider selection | Per-member riders |
| `quoteAmount` | When category Premium/SA | See table above | Input to pricing |

---

## `product` section

| Field | Required | When | Why |
|-------|----------|------|-----|
| `product` (LOB / product type) | Yes | Always | Tells 1SB which product family to resolve |
| `insuranceAndProducts[]` | Conditionally | **Mandatory for Single Quote** (restrict insurer/products) | Pins manufacturer + productCode list |
| `productCode` | Conditionally | Single Quote | Exact product |
| `policyTerm` | Conditionally | Single Quote | Coverage term |
| `premiumPaymentTerm` | Conditionally | Single Quote | PPT |
| `premiumPaymentFrequency` | Conditionally | Single Quote | `M|Q|HY|Y|S` |
| `premiumPaymentOption` | Conditionally | Single Quote | `1` Single / `2` Regular / `3` Limited |
| `planOption` / `coverOption` / `DBPoption` | Conditionally | Single Quote when product has options | Benefit structure |
| Riders / AddOns / ROP / newOptions | Optional | Product dependent | Benefits customization |

**Multi-Quote tip:** send LOB + customer risk inputs; let 1SB resolve product set. Bank `mode` omitted or `MULTI` → `typeOfQuote=Multi-Quote`.  
**Single Quote tip:** bank `mode=SINGLE` plus `selection.insurerCode` and `selection.productCodes[]` (required; 422 otherwise). Adapter maps to 1SB `insuranceAndProducts[].insuranceCompanyCode` + `productCode[]` — not `manufacturerId`.

---

## Response / poll essentials

| Field | Why |
|-------|-----|
| `reqId` | Correlation for poll + later stages |
| `data.isPollComplete` | Stop condition (`true`/`false`) |
| `data.quote` / product array | Offers: premium, SA, logos, BI links, product metadata |
| `errors[]` (global or per manufacturer) | Partial failures in multi-quote |

Persist manufacturer `productCode`, premiums, and BI URLs on bank `QuoteOffer`.

---

## Minimal Multi-Quote mental payload (illustrative)

```json
{
  "typeOfQuote": "Multi-Quote",
  "quoteCategory": "Sum Assured",
  "includeBI": "withoutBI",
  "outOfBoundConfig": "Yes",
  "additionalSetup": { "currency": "INR", "userCountry": "IN" },
  "distributor": {
    "distributorID": "D002",
    "agentID": "SP12345",
    "channelType": "B2B",
    "agentType": "SP"
  },
  "personalInformation": {
    "individualDetails": [
      {
        "memberType": "Life Assured",
        "memberSequenceNumber": 1,
        "gender": "Male",
        "dateOfBirth": "1990-04-12",
        "annualIncome": 1500000,
        "tobacco": "No",
        "zipCode": "400001",
        "quoteAmount": 10000000
      }
    ]
  },
  "product": {
    "product": "LifeTerm"
  }
}
```

Exact nesting must follow the portal schema (adapter unit tests should assert against 1SB sandbox fixtures).
