# 10. Known gaps

Two kinds of gap: features not built yet, and places where the code behaves differently from what its comments or schema suggest. Each entry was checked against the code. Remove an entry when you fix it.

## Not built yet

| Gap | Where | Effect |
|---|---|---|
| Book style guide and chapter context not populated | `book_context` table; the use case passes `""` | the prompt carries only the target language in `system[1]` |
| Glossary matches base forms only | `GlossaryMatcher` | a plural or inflected occurrence ("ventricles") does not match the term ("ventricle"), so such blocks get no glossary hint |
| No check that a glossary rendering was used | `TranslationQualityGate` | the model is told to use the glossary, but nothing verifies it did. An exact-match check would flag every correctly inflected rendering in Romance and Slavic languages |
| Glossary extraction is never exercised by a dry run | `GlossaryService.extend` | dry runs must not write fake renderings, so the extraction path is only covered by unit tests until the first live run |
| No font-size `HEURISTIC` chapter detection | `ChapterDetectionService` | a PDF with neither bookmarks nor a readable printed TOC becomes one "Full text" chapter |
| Printed TOC: only the first 50 pages, and only "title … page" lines | `TocParser` | a TOC further in, a scanned TOC, or one with page numbers on the left is not found |
| No OCR for scanned pages | `PageKind.SCANNED` | scanned pages carry no text and stay untranslated |
| Rotated pages are not overlaid | `OverlayPdfRenderer` | they are reflowed instead |
| Three-column layouts | `ColumnDetector` | treated as one column, so lines merge (no text is lost) |
| No server-side refusal fallback | `AnthropicTranslationAdapter` | refusals are handled client-side by splitting the batch |

## Behaviour that differs from the design

| Gap | Where | Effect |
|---|---|---|
| Jobs are not resumed after a restart | `JobCoordinator` | unfinished jobs are marked `FAILED` at startup and must be re-run by hand. Translation memory makes a re-run cheap |
| `ArtifactKind.DEBUG_LAYOUT` and `JobType.STUDY_GUIDE` are unused | `jobs.domain` | debug PDFs are returned inline, and study guides run inside `TRANSLATE` jobs |
| Progress broker is in memory | `JobProgressBroker` | single instance only. Scaling out needs a shared bus |
