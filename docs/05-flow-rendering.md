# 5. Rendering flow

**Classes:** `OverlayPdfRenderer`, `ReflowPdfRenderer`, `ScriptAwareLineBreaker`, `FilesystemFontProvider`.

`TranslateChaptersUseCase` picks the `PdfRendererPort` whose `mode()` matches the request: `OVERLAY` (the default) or `REFLOW`.

| Mode | Output | Keeps figures and tables | When to use |
|---|---|---|---|
| `OVERLAY` | the original pages with only the words replaced | yes, untouched and still vector | the default |
| `REFLOW` | a newly typeset document (HTML → PDF) with original page numbers in the margin | no | text-only reading, or when the layout is hopeless |

## 5.1 Overlay: page by page

```mermaid
flowchart TD
    S["Resolve font for target script"] --> SF{"found?"}
    SF -->|no| X["FontUnavailableException<br/>job FAILED, message names the directories searched"]
    SF -->|yes| P["For each page in order"]
    P --> PL["planReplacements:<br/>translatable blocks whose text changed"]
    PL --> FIT["fit each block (ladder below)"]
    FIT --> Q{"page rotated, or<br/>any block unfittable?"}
    Q -->|yes| RQ["add page to reflow queue<br/>Overflow finding per unfittable block"]
    Q -->|no| DR["drain reflow queue first<br/>(keeps page order)"]
    DR --> IMP["import original page"]
    IMP --> RAS["rasterise at 72 DPI<br/>to sample background colours"]
    RAS --> MASK["draw ALL masks"]
    MASK --> TXT["then draw ALL text"]
    TXT --> P
    RQ --> P
    P -->|done| END["drain reflow queue, save PDF"]
```

**Masks before text, never interleaved.** A grown block can extend into the whitespace above its neighbour, so drawing mask, text, mask, text would let the next mask cover the previous block's last line.

## 5.2 The fitting ladder

```mermaid
flowchart LR
    A["height budget = top of next block<br/>in the same column − 2 pt<br/>(last block: 94% of page height)"] --> B["leading 1.18"]
    B --> C["font scale 1.00 → 0.80<br/>step 0.02"]
    C -->|fits| OK["Replacement"]
    C -->|still too tall| D["leading 0.92 × 1.18"]
    D --> E["font scale 1.00 → 0.80"]
    E -->|fits| OK
    E -->|still too tall| U["unfittable → whole page reflows"]
```

- Lines are wrapped by `ScriptAwareLineBreaker`, which respects scripts without spaces between words.
- Masks are padded by 1.2 pt, plus 28% of the font size below the box so descenders are covered.
- The mask colour is sampled from just outside each block. This handles tinted panels but not gradients or photographs.

## 5.3 Reflow

`ReflowPdfRenderer` builds HTML from the translated blocks in reading order, adds CSS chosen for the script, and renders it with OpenHTMLtoPDF using the same resolved font. It is used directly for `REFLOW` requests and also as the per-page fallback inside the overlay renderer.

## 5.4 Fonts

```mermaid
flowchart LR
    L["LanguageTag → Script"] --> D1["./fonts (medlearn.fonts.dir)"]
    D1 -->|no match| D2["system font directories<br/>(DejaVu etc.)"]
    D2 -->|no match| E["FontUnavailable"]
```

Noto Sans and Noto Serif are bundled in `fonts/` and cover Latin, Cyrillic and Greek. For CJK, drop a Noto Sans CJK file into `fonts/`. The font is subset-embedded, so only the glyphs actually used are written.
