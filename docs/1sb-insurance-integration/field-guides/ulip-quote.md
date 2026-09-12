# ULIP quote — portal-aligned (Saving API + ULIP filter)

**Status:** `DOC-020` aligned to 1SB portal (2026-09-03) under `CR-014` / `EPIC-002`  
**Quote API:** `POST /insurance/lifesave/v1/quote` with `product.savingsProductType=["ULIP"]`  
**Poll:** `GET /insurance/lifesave/v1/quote/poll/:requestId`  
**LOB discriminator (bank):** `lob=ULIP`  
**Product type (1SB):** `LifeSave` (ULIP is a Saving subtype filter, not a separate path prefix)

Gateway hub: [Insurance Gateway API](https://docs.1silverbullet.tech/docs/insurance/retail/apiDocs/insurance-gateway-api)

## Important portal fact

There is **no** `/insurance/lifeulip/v1/quote` in the retail portal sitemap. ULIP appears as:

1. A value of `savingsProductType` on the **Saving** quote request (`nonParticipating` | `Participating` | `ULIP`)
2. Supplementary Saving-category ops:
   - [ULIP list](https://docs.1silverbullet.tech/docs/insurance/retail/apiDocs/ulip-list-saving-consumer-request-insurance-v-1-consumer-insurance-post)
   - [ULIP performance](https://docs.1silverbullet.tech/docs/insurance/retail/apiDocs/ulip-performance-saving-consumer-request-insurance-v-1-consumer-insurance-post)

## Quote controls

Same envelope as [savings-quote.md](./savings-quote.md). Handler sets:

| Field | Value |
|-------|-------|
| `product.productType` | `LifeSave` |
| `product.savingsProductType` | `["ULIP"]` |

## Supplementary APIs (not the quote submit)

| API | Use on demo |
|-----|-------------|
| ULIP list (`…/quote/ulipList`) | ALB stub — **not** a fund list |
| ULIP performance | Guessed paths 404 — **not** a real demo API |

Do **not** invent a second quote base path, a `/lifeulip` prefix, or a bank `/ulip/list` that pretends to be 1SB.

Funds **do** appear on the Saving quote poll under `productDetails.planOption.investmentOptions.fundDetails`. The adapter maps those rows onto bank `QuoteOffer.funds` (`FUNC-026`). GET `/v1/quotes/{jobId}` returns them after poll completes.

## Mapping notes

- Handler: `UlipQuoteHandler` shares `/insurance/lifesave/v1/…` with Savings — no `/lifeulip` prefix
- Typed body: `LifeQuoteRequest` with ULIP filter
- Distributor: serialise `agentId` (Saving schema); `agentID` alone fails product matching
- Proposal path family: Saving proposal endpoints (portal `submit-saving-proposal-form-…`)
