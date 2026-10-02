# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Spring Boot 4 / Java 25 service: upload a medical textbook PDF, pick chapters, get back a translated PDF that keeps the original layout, plus a study guide in the same language. `README.md` has the endpoint table, request bodies, and known limitations. Read its "Design notes" section before changing extraction, rendering, or prompt code.

## Documentation (keep in sync, always)

`docs/` holds the technical documentation. `docs/README.md` is the index. It covers the HLD, the data model and state machines, sequence diagrams for each flow (ingestion, translation, rendering, study guide, jobs/SSE, glossary in `11-flow-glossary.md`), the API reference, configuration, and `10-known-gaps.md`. All diagrams are Mermaid.

**Every change must update the affected docs in the same change**, and must update this file too when commands, structure or invariants change. In particular:
- An endpoint, request or response shape, or status code → `08-api-reference.md`, plus the flow page that shows it.
- A table or migration → `02-data-model.md`.
- A configuration key → `09-configuration-and-operations.md`.
- A changed flow, or a renamed or moved class → the matching `NN-flow-*.md`. Grep `docs/` for the old class name.
- A gap fixed or discovered → `10-known-gaps.md`.

To check that every Mermaid block still parses, run a script with `mermaid.parse()` under jsdom. The doc pages never reference dates or line numbers; refer to classes by name.

## Commands

```bash
docker-compose up -d                  # Postgres 17 on host port 5433 (standalone binary, not `docker compose`)
mvn spring-boot:run                   # app on http://localhost:8090 (UI at /, API under /api/v1)
mvn test                              # full suite; never calls the Anthropic API
mvn test -Dtest=PageSegmenterTest     # single class
mvn test -Dtest=PageSegmenterTest#methodName
mvn test -Dtest=HexagonalArchitectureTest   # boundary check, run after moving or adding classes
```

- Ports are 8090 and 5433 on purpose. Spring Boot's docker-compose integration is disabled (`spring.docker.compose.enabled: false`) because only the standalone `docker-compose` binary is installed.
- Flyway (`src/main/resources/db/migration`) creates the schema at startup. Add new `V<n>__*.sql` files; don't edit `V1`.
- `--enable-native-access=ALL-UNNAMED` is set for both run and surefire because zstd-jni loads a native library. Any new way of launching the JVM needs it too.
- Env overrides: `SERVER_PORT`, `SPRING_DATASOURCE_URL`, `ANTHROPIC_API_KEY`.
- Runtime data lives in `./data/blobs` (content-addressed PDFs) and `./fonts`. Both are gitignored.

## Live vs. dry run

The app has to start and work fully **without** `ANTHROPIC_API_KEY`. `config/AnthropicConfig` is `@ConditionalOnExpression` on the key and is the only place the `Anthropic*Adapter` beans are created. The `DryRun*Adapter`s are always present. `TranslateChaptersUseCase.selectModel` picks a `TranslationModelPort` by `billable() != dryRun`. Keep this arrangement: requiring the key at boot would make the free path unreachable.

Request `dryRun: true` to exercise extraction, overlay fitting, reflow fallback and font glyph coverage at no cost. Use `GET /api/v1/textbooks/{id}/debug/layout` to see extraction problems. It produces an annotated PDF with blocks numbered in reading order and coloured by column.

## Architecture

Hexagonal layout under `com.medlearn`. The bounded contexts are `library` (catalog: textbooks and chapters), `ingestion` (PDF to `PageLayout`), `translation`, `learning` (study guides), `jobs` (async jobs, SSE progress, artifacts) and `maintenance` (the orphan-file sweep). They sit on top of `shared` (IDs, `DomainError`, cross-cutting ports) and `platform` (blob store, font provider, zstd, `@ConfigurationProperties` records). `config/` holds Spring wiring that crosses contexts, such as the executors and the conditional Anthropic beans.

Every context follows the same layout:
- `domain/`: pure Java, plus `domain/port/*` outbound port interfaces.
- `application/`: use cases and services, which depend on ports only.
- `adapter/web`: controllers (the only inbound adapter).
- `adapter/{persistence,pdf,ai,render}`: plain JDBC repositories, PDFBox, the Anthropic SDK, OpenHTMLtoPDF.

`HexagonalArchitectureTest` (ArchUnit) fails the build when any of these rules break:
- `domain` imports Spring, PDFBox, Jackson (2 or 3), Anthropic, zstd, OpenHTMLtoPDF, `jakarta.*`, `javax.sql`, or `com.medlearn.platform`.
- `domain` or `application` depends on any `adapter` package.
- `library.domain` or `library.application` depends on `ingestion`, or anything in `library` depends on `jobs`.
- Any cycle between top-level packages. This includes `config`, so `config` must not be referenced from the contexts.

The allowed one-way dependencies between contexts:
- `ingestion → library.domain`: ingestion writes the catalog.
- `jobs → library.domain.port`: `JobArtifactCleanup` implements `TextbookDependents`, which lets a textbook delete clear its jobs and artifact files. `library` must never import `jobs`, and ArchUnit enforces this.
- `translation`/`learning → ingestion.domain`: `PageLayout` is a published language.
- `translation → learning`: translation triggers study-guide generation.

### Pipeline flow

1. **Upload** (`IngestTextbookUseCase`): streams the PDF to the blob store, hashing as it goes. Duplicate bytes produce an `AlreadyExists` outcome. Otherwise the use case submits a job through `JobCoordinator` and returns `202`.
2. **Extract**: runs pages in parallel on the bounded `extractionExecutor`. `PageSegmenter` detects columns first and then groups lines within each column (never join lines across the gutter). `HeaderFooterFilter` removes running heads and folios, and `ParagraphMerger` joins paragraphs. `LayoutCodec` stores the result zstd-compressed in `page_layout`.
3. **Chapters** (`ChapterDetectionService`): reads the PDF bookmark tree. Without bookmarks, `TableOfContentsDetector` runs at the end of the ingest job. It re-reads the first 50 pages as lines, parses the printed TOC (`TocParser`) and maps printed page numbers to PDF pages (`TocPageMapper`). For both sources, `OutlineTreeBuilder` closes each range against the next entry that is not a descendant.
4. **Translate** (`TranslateChaptersUseCase`):
   - On live runs, `GlossaryService.extend` first scans pages not yet scanned for this language and adds terms. Then the book's glossary for the language is loaded into a `GlossaryMatcher`.
   - `BatchPlanner` splits blocks into batches by character budget.
   - On live runs, translation memory is checked first, keyed by text hash plus the glossary key (the fingerprint of the terms in the block), so a repeat run makes 0 API calls. Dry runs never read or write the memory, because its key doesn't include the model and fake text would leak into live runs.
   - `PromptAssembler` builds the cached system prefix.
   - Every block goes through `TranslationQualityGate`. A refused batch is split and retried per block.
5. **Render**: `OverlayPdfRenderer` replaces words on the original page and falls back to reflow per page when text won't fit. `ReflowPdfRenderer` uses HTML→PDF. The output is one PDF per request, stored as a `jobs` artifact.
6. **Study guide** (`GenerateStudyGuideUseCase`): an optional step called from the translation job.

`JobCoordinator` runs work on the virtual-thread `jobExecutor` and persists `JobState` to the DB. `JobProgressBroker` fans progress out to SSE subscribers.

## Invariants that are easy to break

- **Cancellation goes through `ProgressSink.cancelled()`.** Never use `Thread.currentThread().isInterrupted()` for this: only the job thread is interrupted, and work fans out to other executors. Any loop that runs per page, per window or per batch must check `cancelled()`. Any stage that produces an artifact must check it first.
- **A textbook never stays `UPLOADED` or `EXTRACTING` without a running job.** A failed or cancelled ingest sets it to `FAILED`. Startup recovery (`IngestionRecovery`, `JobCoordinator.afterSingletonsInstantiated`) fails anything left unfinished. Re-uploading the bytes of a `FAILED` textbook retries it.
- **Quality findings are persisted, not just logged.** New `QualityFinding` types need a `kind` in `FindingReport` (the exhaustive switch enforces this). They reach the job as generic `JobFinding`s through `JobCoordinator.recordFindings`, and `jobs` must never import `translation`.
- **Code that stores blobs must report its references.** Each context that writes files implements `BlobReferencesPort` for its own rows; today that is `JdbcTextbookRepository` and `JdbcArtifactRepository`. The orphan sweep (`OrphanBlobSweeper`) deletes any file older than an hour that no implementation reports, so a new kind of stored file without one would be deleted.
- **TOC detection rejects rather than guesses.** A TOC that can't be parsed or placed leaves the book with one "Full text" chapter, never chapters on the wrong pages. It needs line-level text (`Session.extractLines`), because stored layouts merge TOC entries into paragraphs.
- **The glossary only grows automatically.** Extraction adds terms but never changes an existing rendering; only a reader's edit does, and it pins the term. Dry runs never extract, because fake renderings would reach live runs. Source terms are unique per book and language, ignoring case.
- **The translation-memory key includes the glossary.** It is `(hash of the text, language, GlossaryMatcher.fingerprint(terms in the block))`. Don't go back to a per-book version number: memory is shared across books, and a version bump would invalidate every block instead of only those that use the term.
- **The prompt glossary is computed once per job and sorted.** It's the last block of the cached prefix, so it must be identical in every batch.
- **Two executors.** Virtual threads (`jobExecutor`) are for blocking work: jobs and API calls. PDF parsing and rasterizing run on the bounded platform pool `extractionExecutor`, where each worker holds its own `PDDocument`. Never move extraction onto virtual threads. API concurrency is capped by `medlearn.translation.max-in-flight`, enforced by one fair semaphore (`ApiSlots`) shared by all translation jobs and glossary extraction, not one per job. Every batch, including the seed batch, must take a slot, and must check cancellation after getting it.translation.max-in-flight` / `medlearn.anthropic.max-in-flight`.
- **Coordinates.** Extraction measures y downward from the top of the page. PDF drawing measures it upward from the bottom. `BlockBox.toPdfSpaceY` is the only conversion. Don't add another.
- **Overlay order.** Draw every mask first, then all the text. Masking and drawing each block in turn lets the next block's mask paint over text that has already been drawn.
- **Byte-stable prompt prefix.** The system blocks are instructions, then style guide, then glossary, with the cache breakpoint on the last *shared* block. The glossary is always sorted. Never put timestamps, unsorted collections or per-request data in the prefix. If `cacheReadInputTokens` stays at zero, the prefix changed.
- **Numbers are verified.** `TranslationQualityGate` compares the multiset of numbers in the source and the output. Decimal separators are normalised, so `0,5` matches `0.5`. Don't loosen this check: a wrong dose is a clinical hazard.
- **Two Jackson majors coexist.** Web payloads use Jackson 3 (`tools.jackson`) through Spring Boot 4. Jackson 2 (`com.fasterxml`) is used only for Anthropic SDK structured-output DTOs and `LayoutCodec`. Keep both out of `domain`.
- **PDFBox versions are pinned** in `dependencyManagement` so that OpenHTMLtoPDF can't pull in a mismatched set.
- **Flyway needs the starter.** It is enabled through `spring-boot-starter-flyway`. Bare `flyway-core` silently never migrates on Boot 4.
- **Fonts.** Missing glyph coverage must throw `FontUnavailableException`, never render as blank boxes. CJK targets need a Noto CJK font placed in `fonts/` by hand.
