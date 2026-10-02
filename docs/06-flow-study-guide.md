# 6. Study guide flow

**Classes:** `GenerateStudyGuideUseCase`, `AnthropicStudyGuideAdapter`, `DryRunStudyGuideAdapter`, `StudyGuideSchema`, `StudyGuidePdfRenderer`, `StudyGuide`.

There is no standalone endpoint. A study guide is produced inside a `TRANSLATE` job when the request has `includeStudyGuide: true`, after the translated PDF has been stored.

```mermaid
sequenceDiagram
    autonumber
    participant T as Translate job
    participant G as GenerateStudyGuideUseCase
    participant M as StudyGuideModelPort
    participant R as StudyGuidePdfRenderer
    participant A as BlobStore + artifact

    Note over T: for each selected chapter, join the TRANSLATED text<br/>of its pages (source text where a block has no translation)
    T->>G: generate(chapterTexts, lang, title, dryRun)
    G->>G: pick model with billable() != dryRun
    loop each chapter with non-blank text
        G->>M: generate(title, text, lang)
        M-->>G: StudyGuide
    end
    alt no chapter produced a guide
        G-->>T: throw → job FAILED
    end
    G->>R: render(guides, lang, title)
    R-->>G: PDF bytes
    G-->>T: Rendered
    T->>A: store STUDY_GUIDE artifact
```

## Design points

- **Built from the translated text.** The guide uses the same terms the reader just saw, rather than inventing a second translation of each term.
- **One request per chapter.** A single request for eight chapters produces eight shallow summaries.
- **Structured output.** `StudyGuide` has objectives, key concepts, mechanisms, clinical pearls, red flags, high-yield facts and self-test items. The schema and its field descriptions, which act as prompt text, live in `StudyGuideSchema` beside the adapter. The domain record has no annotations.
- **Effort `HIGH`.** Translation uses `LOW`. Summarising is synthesis rather than transformation, and there is only one call per chapter.
- **The glossary is passed as a map** from source term to agreed rendering, covering the terms that occur in the selected chapters. The guide then names concepts exactly as the translated chapter did.
