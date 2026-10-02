package com.medlearn.ingestion.application;

import com.medlearn.ingestion.domain.OutlineNode;
import com.medlearn.ingestion.domain.OutlineTreeBuilder;
import com.medlearn.ingestion.domain.OutlineTreeBuilder.FlatEntry;
import com.medlearn.ingestion.domain.PageLayout;
import com.medlearn.ingestion.domain.TextBlock;
import com.medlearn.ingestion.domain.TextRole;
import com.medlearn.ingestion.domain.TocPageMapper;
import com.medlearn.ingestion.domain.TocParser;
import com.medlearn.ingestion.domain.TocParser.ParsedToc;
import com.medlearn.ingestion.domain.port.LayoutExtractorPort;
import com.medlearn.ingestion.domain.port.PageLayoutRepository;
import com.medlearn.jobs.application.JobCoordinator;
import com.medlearn.library.domain.ChapterSource;
import com.medlearn.platform.config.IngestionProperties;
import com.medlearn.shared.PageRange;
import com.medlearn.shared.TextbookId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

/**
 * Finds chapters in a printed table of contents, for books whose PDF has no bookmarks.
 *
 * <p>Runs after page extraction and the furniture pass, because it needs both: the
 * recognised page numbers place the TOC's printed page numbers on PDF pages, and the body
 * text confirms that chapter titles are where the TOC says.
 *
 * <p>Reads the opening pages again, line by line. The stored layouts merge lines into
 * paragraphs, and consecutive TOC entries look exactly like the lines of one paragraph.
 */
@Service
public class TableOfContentsDetector {

    private static final Logger log = LoggerFactory.getLogger(TableOfContentsDetector.class);

    /** Pages after the TOC whose printed numbers are sampled to find the offset. */
    private static final int FOLIO_SAMPLE_PAGES = 300;

    private final LayoutExtractorPort extractor;
    private final PageLayoutRepository pageLayouts;
    private final ChapterDetectionService chapterDetection;
    private final IngestionProperties properties;

    public TableOfContentsDetector(
            LayoutExtractorPort extractor,
            PageLayoutRepository pageLayouts,
            ChapterDetectionService chapterDetection,
            IngestionProperties properties) {
        this.extractor = extractor;
        this.pageLayouts = pageLayouts;
        this.chapterDetection = chapterDetection;
        this.properties = properties;
    }

    /** @return chapters from the TOC, or empty if there is none or it cannot be placed */
    public Optional<ChapterDetectionService.DetectedChapters> detect(
            TextbookId textbookId, Path file, int pageCount, JobCoordinator.ProgressSink progress) {

        SortedMap<Integer, List<TextBlock>> lines = new TreeMap<>();
        try (LayoutExtractorPort.Session session = extractor.open(file)) {
            for (int page = 1; page <= Math.min(pageCount, TocParser.SCAN_PAGES); page++) {
                if (progress.cancelled()) return Optional.empty();
                lines.put(page, session.extractLines(page));
            }
        }

        Optional<ParsedToc> parsed = TocParser.parse(lines);
        if (parsed.isEmpty()) {
            log.info("No table of contents found in the first {} pages of {}",
                    lines.size(), textbookId);
            return Optional.empty();
        }
        ParsedToc toc = parsed.get();
        int firstContentPage = toc.lastPage() + 1;

        Optional<TocPageMapper.Mapping> mapping = TocPageMapper.map(
                toc.entries(), folios(textbookId, firstContentPage, pageCount),
                bodyText(textbookId), firstContentPage, pageCount);
        if (mapping.isEmpty()) {
            log.info("Found a table of contents on pages {}-{} of {} with {} entries, but could not "
                            + "place its page numbers in the PDF; not using it",
                    toc.firstPage(), toc.lastPage(), textbookId, toc.entries().size());
            return Optional.empty();
        }

        int offset = mapping.get().offset();
        List<FlatEntry> entries = toc.entries().stream()
                .map(entry -> new FlatEntry(entry.title(), entry.level(), entry.printedPage() + offset))
                .filter(entry -> entry.startPage() >= firstContentPage && entry.startPage() <= pageCount)
                .toList();
        List<OutlineNode> tree = OutlineTreeBuilder.close(OutlineTreeBuilder.nest(entries), pageCount);

        log.info("Table of contents on pages {}-{} of {}: {} entries, printed page 1 = PDF page {} ({})",
                toc.firstPage(), toc.lastPage(), textbookId, entries.size(), 1 + offset,
                mapping.get().basis());
        return Optional.of(chapterDetection.detect(textbookId, tree, pageCount, ChapterSource.TOC));
    }

    /** Printed page number per PDF page, from the folios the furniture pass recognised. */
    private Map<Integer, Integer> folios(TextbookId textbookId, int fromPage, int pageCount) {
        Map<Integer, Integer> printed = new HashMap<>();
        int lastPage = Math.min(pageCount, fromPage + FOLIO_SAMPLE_PAGES - 1);
        int window = properties.pageWriteBatchSize();
        for (int start = fromPage; start <= lastPage; start += window) {
            for (PageLayout page : pageLayouts.findRange(
                    textbookId, PageRange.of(start, Math.min(lastPage, start + window - 1)))) {
                page.blocks().stream()
                        .filter(block -> block.role() == TextRole.FOLIO)
                        .map(block -> block.text().strip())
                        .filter(text -> text.matches("\\d{1,4}"))
                        .findFirst()
                        .ifPresent(number -> printed.put(page.pageNo(), Integer.parseInt(number)));
            }
        }
        return printed;
    }

    /** A page's text without running heads and folios, read on demand and cached. */
    private IntFunction<String> bodyText(TextbookId textbookId) {
        Map<Integer, String> cache = new HashMap<>();
        return page -> cache.computeIfAbsent(page, p -> pageLayouts
                .findRange(textbookId, PageRange.of(p, p)).stream()
                .flatMap(layout -> layout.translatableBlocks().stream())
                .map(TextBlock::text)
                .collect(Collectors.joining("\n")));
    }
}
