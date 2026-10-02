# 4. Translation flow

**Classes:** `TranslationController`, `TranslateChaptersUseCase`, `GlossaryService`, `ApiSlots`, `BatchPlanner`, `JdbcTranslationMemory`, `PromptAssembler`, `AnthropicTranslationAdapter`, `DryRunTranslationAdapter`, `TranslationQualityGate`.

A request selects chapters, either by id or by an ordinal range, and produces **one** PDF for the whole selection, plus an optional study guide. All of the work runs as a `TRANSLATE` job.

## 4.1 Request validation (synchronous)

```mermaid
sequenceDiagram
    autonumber
    actor U as Browser
    participant C as TranslationController
    participant UC as TranslateChaptersUseCase
    participant DB as Repositories
    participant J as JobCoordinator

    U->>C: POST /api/v1/textbooks/{id}/translations
    C->>UC: submit(Command)
    UC->>DB: findById(textbook)
    alt not found or not READY
        UC-->>C: Rejected
        C-->>U: 422 { error }
    end
    UC->>DB: chapters by id, or by ordinal fromChapter..toChapter
    alt no chapters matched
        UC-->>C: Rejected(ChapterNotFound)
        C-->>U: 422 { error }
    end
    UC->>J: submit(TRANSLATE, run(...))
    UC-->>C: Accepted(jobId)
    C-->>U: 202 { jobId }
```

A model is chosen when the job runs, not when the request is validated. A live request without an API key therefore returns `202`, and the job then fails with "no live translator is configured".

## 4.2 The `TRANSLATE` job end to end

```mermaid
sequenceDiagram
    autonumber
    participant J as Translate job
    participant DB as page_layout
    participant GL as GlossaryService
    participant TM as Translation memory
    participant M as TranslationModelPort
    participant QG as QualityGate
    participant R as PdfRenderer
    participant SG as GenerateStudyGuideUseCase
    participant A as BlobStore + artifact

    J->>DB: findRange(union of chapter page ranges)
    Note over J: keep translatable blocks only<br/>(no running heads or folios)<br/>fail if none: "pages may be scans"
    opt live run only
    Note over J: phase "building glossary"
    J->>GL: extend(book, lang, pages): scan new pages for terms
    end
    J->>GL: termsFor(book, lang) → matcher
    opt live run only
    J->>TM: findAll(keys: hash(text) + glossary key, lang)
    TM-->>J: cached translations
    end
    Note over J: phase "translating"
    J->>M: batch 1 (alone, seeds the prompt cache), with the glossary terms in the text
    M-->>J: translated blocks
    J->>QG: inspect each block
    par remaining batches, at most max-in-flight in flight across ALL jobs
        J->>M: batch k
        M-->>J: translated blocks
        J->>QG: inspect each block
    end
    opt live run only
    J->>TM: putAll(blocks with no findings)
    end
    J->>A: recordFindings(gate, refusal, missing-block findings)
    Note over J: phase "rendering"
    J->>R: render(source PDF, pages, translations)
    R-->>J: PDF bytes + findings
    J->>A: store TRANSLATION artifact
    J->>A: recordFindings(render findings, e.g. Overflow)
    opt includeStudyGuide
        Note over J: phase "writing study guide"
        J->>SG: generate(translated text per chapter)
        SG-->>J: PDF bytes
        J->>A: store STUDY_GUIDE artifact
    end
```

**Concurrency.** Every batch, the seed batch included, holds one of `ApiSlots`: a fair semaphore of size `medlearn.translation.max-in-flight`, shared by all translation jobs in the process and by glossary extraction. The API rate limit belongs to the account, so two jobs running together share it instead of each taking 8. The slot is taken before `runBatch`, and `runBatch` checks cancellation, so a batch that waited behind other jobs makes no call once its own job is cancelled.

Progress units are **blocks**. Cache hits are counted immediately, and each batch advances the count by its size.

**Cancellation.** Each batch checks `progress.cancelled()` before calling the model, and `run` checks again before rendering and before the study guide. A cancelled job therefore stops sending batches, keeps the quality-checked translations it already paid for in translation memory, and stores no artifacts. See [Jobs, progress and cancellation](07-flow-jobs-and-progress.md#72-cancellation).

## 4.3 Translation memory

```mermaid
flowchart LR
    D{"dry run?"} -->|yes| P
    D -->|no| B
    B["TextBlock text"] --> H["SHA-256 of text"]
    B --> GK["glossary key:<br/>hash of glossary terms<br/>occurring in the block"]
    H --> K{"(hash, lang,<br/>glossary key)<br/>in translation_unit?"}
    GK --> K
    K -->|hit| T["use cached text<br/>no API call"]
    K -->|miss| P["pending → BatchPlanner"]
    P --> Q{"quality gate<br/>clean?"}
    Q -->|"yes, live run"| S["cache it"]
    Q -->|"no, or dry run"| N["use it, but do NOT cache it"]
```

Translating the same range again live makes zero API calls. Suspect translations are never cached, because caching would make the error permanent and invisible. The glossary key makes a term edit re-translate exactly the blocks that contain the term. See [Glossary flow §11.6](11-flow-glossary.md#116-how-an-edit-reaches-the-pages-the-memory-key).

**Live runs only.** The memory key is content, language and glossary key, not the model. If dry-run output were cached, a later live run would be served the fake text. Dry runs therefore neither read nor write the memory (`useMemory = model.billable()`). A side benefit: every block in a dry run goes through expansion and fitting. Migration `V2` removed dry-run entries written before this rule existed.

## 4.4 Batching

`BatchPlanner.intoBatches` groups pending blocks in document order. Each batch stays under `medlearn.translation.max-chars-per-batch` (24 000) characters. The limit is on characters, not block count, because blocks range from a two-word heading to a 900-character paragraph.

## 4.5 One model call (live adapter)

```mermaid
sequenceDiagram
    autonumber
    participant UC as Use case
    participant AD as AnthropicTranslationAdapter
    participant API as Anthropic Messages API

    UC->>AD: translate(Batch)
    AD->>API: system[0] instructions<br/>system[1] language + style + chapter context<br/>system[2] glossary  ← cache breakpoint<br/>user: <block id="p12-b3">…</block> …
    alt HTTP 429 after SDK retries
        API-->>AD: RateLimitException
        AD-->>UC: throw → job FAILED
    else stop_reason = refusal
        API-->>AD: 200, refusal
        alt batch has > 1 block
            loop each block alone
                AD->>API: translate([block])
            end
        else single block
            AD-->>UC: Refused finding, block left in source language
        end
    else success
        API-->>AD: { translations: [ {id, text}, … ] }
        Note over AD: warn if cacheRead = 0 and cacheWrite = 0
        AD-->>UC: TranslatedBlocks + usage
    end
```

Request settings: structured output keyed by block id, `effort LOW`, adaptive thinking, `max_tokens` 32 000, a 10-minute timeout, and a 5-minute cache TTL.

**Do not break the cache prefix.** `system[0..2]` must be byte-identical across every batch of a chapter. Never put timestamps, counters or unsorted collections in it. The glossary is always sorted by source term.

## 4.6 Quality gate

Every translated block is checked deterministically, at no cost.

```mermaid
flowchart TD
    I["(source, target)"] --> E{"target empty?"}
    E -->|yes| F1["EmptyTranslation"]
    E -->|no| N{"sorted numbers equal?<br/>(0,5 normalised to 0.5)"}
    N -->|no| F2["NumericMismatch"]
    N -->|yes| L
    F2 --> L{"length ratio within<br/>0.4 … 2.5?"}
    L -->|no| F3["ImplausibleLength"]
    L -->|yes| PH
    F3 --> PH{"all ␠XX␠ placeholders<br/>preserved?"}
    PH -->|no| F4["UnrestoredPlaceholder"]
    PH -->|yes| OK["clean → cacheable"]
```

Other findings are raised elsewhere. `Refused` comes from the adapter, `MissingBlock` from the use case when the model returned no entry for a block, and `Overflow` from the overlay renderer.

### Where findings go

```mermaid
flowchart LR
    Q["QualityFinding<br/>(translation.domain)"] --> FR["FindingReport<br/>kind name, source page,<br/>sorted in book order"]
    FR --> JF["JobFinding<br/>(jobs.domain)"]
    JF --> DB[("job.findings jsonb<br/>appended per stage")]
    DB --> API["GET /jobs/{id} → findingCount<br/>GET /jobs/{id}/findings → list"]
    API --> UI["UI: 'N passages to review'<br/>under the downloads"]
```

Findings are recorded twice per job: once after translating (gate, refusal and missing-block findings) and once after rendering (overflow). On cancel, the findings gathered so far are still recorded. `jobs` stores them in its own generic `JobFinding` shape so it never depends on `translation`. The PDF is always delivered; the findings list is what a reader checks it against.

| `kind` | Raised by | Meaning |
|---|---|---|
| `NUMERIC_MISMATCH` | quality gate | numbers or units differ, e.g. a dose. The most serious finding |
| `EMPTY_TRANSLATION` | quality gate | the model returned an empty string |
| `IMPLAUSIBLE_LENGTH` | quality gate | ratio outside `length-ratio-min`…`max`, suggesting truncation or padding |
| `UNRESTORED_PLACEHOLDER` | quality gate | a protected placeholder was lost |
| `MISSING_BLOCK` | use case | the model never returned this block. It stays in the source language |
| `REFUSED` | Anthropic adapter | a safety refusal. The block stays in the source language |
| `OVERFLOW` | overlay renderer | text did not fit, so the page was reflowed |

## 4.7 Dry run

`DryRunTranslationAdapter` makes no network calls. It expands the text by about 25% and injects diacritics, so the overlay fitting ladder, the reflow fallback and font glyph coverage are all exercised at zero cost. It never touches translation memory. Use it before any live run on a new textbook.
