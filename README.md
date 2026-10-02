# Medical Textbook Translation & Learning Service

Upload a medical textbook PDF, pick chapters, and get back a translated PDF that keeps the
original layout plus a study guide in the same language.

**All six phases are implemented and running.** Everything works end to end today except that
live translation needs an `ANTHROPIC_API_KEY`; without one, the **dry-run** path exercises the
entire pipeline for free.

## Running it

```bash
docker-compose up -d       # Postgres on host port 5433
mvn spring-boot:run        # app on http://localhost:8090
```

Open <http://localhost:8090>, upload a PDF, select chapters, translate.

Flyway creates the schema on first start. Override with `SERVER_PORT`,
`SPRING_DATASOURCE_URL`, `ANTHROPIC_API_KEY`.

> Ports 8090 and 5433 rather than 8080 and 5432, both of which are usually taken.
> Spring Boot's docker-compose integration is disabled because this machine has the
> standalone `docker-compose` binary but not the `docker compose` CLI plugin it requires.

### Live translation

```bash
export ANTHROPIC_API_KEY=sk-ant-...
mvn spring-boot:run
```

Without the key the app still starts and dry runs still work; asking for a live translation
returns a clear message instead of failing at startup.

## What it does

| Stage | Behaviour |
|---|---|
| **Upload** | Streams to disk, hashes while streaming, returns `202` + a job. Re-uploading the same bytes is a no-op. |
| **Extract** | Parallel page extraction, column detection, paragraph merging, running-head/folio detection. |
| **Chapters** | From the PDF bookmark tree, or, without one, the printed table of contents, mapped to PDF pages through the printed page numbers. Ranges are closed correctly against the next non-descendant entry. |
| **Translate** | Chapter or chapter range → **one** PDF. A per-book glossary keeps terms consistent; prompt caching, translation memory, per-block quality gate. |
| **Render** | `OVERLAY` keeps the original page and replaces only words; `REFLOW` typesets a clean document. Overlay falls back to reflow per page when text cannot be made to fit. |
| **Study guide** | Objectives, key concepts, mechanisms, pearls, red flags, high-yield facts, self-test — in the target language. |

## Endpoints

| Method | Path | Notes |
|---|---|---|
| POST | `/api/v1/textbooks` | multipart `file`; `202` + job, or `200` if already ingested |
| GET | `/api/v1/textbooks` | list |
| GET | `/api/v1/textbooks/{id}` | detail |
| GET | `/api/v1/textbooks/{id}/chapters` | nested chapter tree |
| GET | `/api/v1/textbooks/{id}/extraction` | pages and characters extracted |
| GET | `/api/v1/textbooks/{id}/debug/layout?from=&to=` | **annotated PDF of extraction** |
| DELETE | `/api/v1/textbooks/{id}` | cascades rows, deletes the stored PDF |
| POST | `/api/v1/textbooks/{id}/translations` | `202` + job |
| GET | `/api/v1/jobs/{id}` · `/events` · `/cancel` | state, SSE stream, cancellation |
| GET | `/api/v1/jobs/{id}/artifacts` | produced files |
| GET | `/api/v1/artifacts/{id}/download` | streamed PDF |
| GET · PUT | `/api/v1/textbooks/{id}/glossary/{lang}` | list terms · add or correct one (pins it) |
| DELETE | `/api/v1/textbooks/{id}/glossary/{lang}/{termId}` | remove a term |
| GET · DELETE | `/api/v1/maintenance/orphan-blobs` | preview · delete stored files nothing references |

Translation request body:

```json
{ "chapterIds": ["..."], "targetLanguage": "ro", "renderMode": "OVERLAY",
  "includeStudyGuide": true, "dryRun": true }
```

`fromChapter` / `toChapter` (chapter ordinals) can be used instead of `chapterIds`.

## Measured on a synthetic textbook

| | |
|---|---|
| Upload response (1000 pages) | 133 ms |
| Extraction, 8 workers | **0.98 s** / 1000 pages |
| `page_layout` storage | **844 KB** / 1000 pages (864 B/page, 3.8x zstd) |
| Translate + render 3 chapters (75 pages, dry run) | 10 s |
| Re-translating the same range (live) | **0 API calls** — served entirely from translation memory. Dry runs bypass the memory |

The fixture repeats a handful of sentences, so it compresses and dedupes better than real
prose. Expect 1–1.5 KB/page on a genuine textbook.

## Two debugging tools worth knowing

**`/debug/layout`** draws every extracted block over the original page, numbered in reading
order and coloured by column. On a correct two-column page the numbering runs down the left
column then down the right; numbering that alternates across the gutter is the column-merge
defect. This finds extraction bugs in seconds instead of by puzzling over odd translations.

**`dryRun: true`** runs the entire pipeline with a translator that calls nothing. It expands
the text ~25% (translations really do run that much longer) and injects diacritics, so it
exercises the overlay fitting ladder, the reflow fallback and font glyph coverage — the three
things most likely to be wrong — at zero cost.

## Design notes worth reading before changing things

**Columns before lines, always.** `PageSegmenter` detects columns from word runs, *then*
groups lines within each column. Joining text first concatenates the left column's sentence
with the right column's unrelated one and produces fluent nonsense that survives translation
looking entirely plausible. The discriminator for a full-width heading is not whether a *run*
crosses the gutter — a heading is many narrow word runs — but whether anything sits *inside*
the gutter: two merged column lines leave it empty.

**Two executors, deliberately.** Virtual threads run jobs and API calls, which are blocking
waits. PDF extraction runs on a **bounded platform pool**: it is real CPU work and each worker
holds its own `PDDocument`, so unbounded virtual threads would mean a thousand concurrent page
parses and an OOM. Neither executor raises throughput against the Anthropic API — that ceiling
is the account's rate limit, which `medlearn.translation.max-in-flight` exists to respect: one
limit shared by every translation job in the process, not one per job.

**One coordinate conversion, in one place.** Extraction reports y downward from the page top;
PDF drawing measures it upward from the bottom. `BlockBox.toPdfSpaceY` is the only conversion,
and a test asserts a known drawn position survives the round trip.

**Masks before text, never interleaved.** A replaced block may grow down into the whitespace
above its neighbour, so masking and drawing each block in turn would let the next block's mask
paint over the previous block's second line. All masks go down first.

**The prompt prefix must stay byte-stable.** Instructions, style guide and glossary are system
blocks with the cache breakpoint on the last one — at the end of the *shared* portion, never
after the per-request blocks. The glossary is always sorted. A timestamp or an unsorted list
anywhere in that prefix silently multiplies the cost of every request with no error at all;
`cacheReadInputTokens` is logged, and a warning fires when it stays zero.

**Numbers are checked, not trusted.** A translation that turns "300 mg" into "30 mg" reads
perfectly and is a clinical hazard. `TranslationQualityGate` compares the multiset of numbers
in source and output, normalising decimal separators so that Romanian "0,5" for English "0.5"
is not a false alarm.

## Architecture

Hexagonal, six bounded contexts — `library`, `ingestion`, `translation`, `learning`, `jobs`,
`maintenance` — over `shared` and `platform`. `domain/` has zero framework imports.
`HexagonalArchitectureTest` fails the build on violations, including cycles between contexts.
It has already caught three real problems: a `library ↔ ingestion` cycle, a
`config ↔ translation/learning` cycle, and Jackson annotations leaking into a domain record.

Deliberate, one-way cross-context dependencies: `ingestion → library.domain` (ingestion writes
the catalog), `jobs → library.domain.port` (jobs implements `TextbookDependents`, so deleting a
textbook also removes its jobs and artifact files), `translation/learning → ingestion.domain` (`PageLayout` as published language),
and `translation → learning` (translation drives study-guide generation, which is why
`learning` knows nothing about `translation`).

## Fonts

Noto Sans/Serif are in `fonts/`; DejaVu is picked up from the system as a fallback. Both cover
Latin, Cyrillic and Greek.

**CJK needs a manual step**: drop `NotoSansCJK-Regular.otf` (or similar) into `fonts/`. Those
faces are ~20MB each and are not bundled. If a font covering the target language is missing,
rendering fails with a message naming the directories searched — rather than silently
producing a document of blank boxes.

## Tests

```bash
mvn test        # 70 tests
```

Nothing in the suite calls the Anthropic API or costs money.

## Known limitations

- **Overlay masking is tuned against synthetic pages.** Background colour is sampled from
  just outside each block, which handles white and tinted panels; gradients and photographs
  behind text may show a visible patch. Worth checking with `/debug/layout` and a dry run on
  a real textbook first.
- **Rotated pages are reflowed, not overlaid.** Overlaying them correctly needs a coordinate
  transform that is not implemented; reflowing is honest rather than approximately wrong.
- **Three-column layouts degrade to single-column**, which merges lines rather than losing
  them. Medical textbooks are one or two columns in practice.
- **Scanned pages are detected but not yet OCR'd.** They are marked `SCANNED` and carry no
  text; the vision fallback is not implemented.
- **Glossary terms match base forms only.** A plural or inflected occurrence ("ventricles")
  does not match its term ("ventricle"), and nothing checks that the model actually used a
  glossary rendering. See `docs/11-flow-glossary.md`.
- **Server-side refusal fallbacks are not used.** Refusals are handled client-side instead:
  a refused batch is split and retried per block, and persistently refused blocks are reported
  and left in the source language. The beta `fallbacks` parameter does not compose with the
  typed structured-output path in this SDK version, and shipping untested beta plumbing was
  the worse option.
