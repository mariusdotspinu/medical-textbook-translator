package com.medlearn.translation.application;

import com.medlearn.ingestion.domain.BlockBox;
import com.medlearn.ingestion.domain.FontInfo;
import com.medlearn.ingestion.domain.PageKind;
import com.medlearn.ingestion.domain.PageLayout;
import com.medlearn.ingestion.domain.TextBlock;
import com.medlearn.ingestion.domain.TextRole;
import com.medlearn.jobs.application.JobCoordinator;
import com.medlearn.library.domain.Textbook;
import com.medlearn.library.domain.port.TextbookRepository;
import com.medlearn.platform.config.TranslationProperties;
import com.medlearn.shared.BlobRef;
import com.medlearn.shared.ContentHash;
import com.medlearn.shared.JobId;
import com.medlearn.shared.LanguageTag;
import com.medlearn.shared.PageRange;
import com.medlearn.shared.TextbookId;
import com.medlearn.translation.domain.GlossaryTerm;
import com.medlearn.translation.domain.GlossaryTerm.TermKind;
import com.medlearn.translation.domain.GlossaryTermId;
import com.medlearn.translation.domain.port.GlossaryRepository;
import com.medlearn.translation.domain.port.TermExtractionPort;
import com.medlearn.translation.domain.port.TermExtractionPort.ProposedTerm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GlossaryServiceTest {

    private static final LanguageTag RO = LanguageTag.of("ro");

    private final InMemoryGlossary repository = new InMemoryGlossary();
    private final TextbookRepository textbooks = mock(TextbookRepository.class);
    private final TermExtractionPort extractor = mock(TermExtractionPort.class);
    private final GlossaryService service = new GlossaryService(
            repository, textbooks, List.of(extractor),
            new ApiSlots(new TranslationProperties(24_000, 2, 0.4, 2.5)));

    private final TextbookId bookId = TextbookId.newId();

    @Test
    @DisplayName("proposals the text does not contain, duplicates and non-translations are rejected")
    void acceptsOnlyCheckedProposals() {
        List<GlossaryTerm> known = List.of(term("heart failure", "insuficiență cardiacă", false));
        String text = "Heart failure with reduced ejection fraction follows myocardial infarction.";

        List<GlossaryTerm> accepted = GlossaryService.accept(List.of(
                new ProposedTerm("myocardial infarction", "infarct miocardic", TermKind.CONDITION),
                new ProposedTerm("Heart Failure", "insuficiență", TermKind.CONDITION),    // known
                new ProposedTerm("ejection fraction", "ejection fraction", TermKind.OTHER), // same
                new ProposedTerm("cardiogenic shock", "șoc cardiogen", TermKind.CONDITION), // absent
                new ProposedTerm("  ", "x", TermKind.OTHER),
                new ProposedTerm("Myocardial infarction", "IM", TermKind.CONDITION)),    // repeat
                known, text, RO);

        assertThat(accepted).singleElement().satisfies(term -> {
            assertThat(term.sourceTerm()).isEqualTo("myocardial infarction");
            assertThat(term.targetTerm()).isEqualTo("infarct miocardic");
            assertThat(term.kind()).isEqualTo(TermKind.CONDITION);
            assertThat(term.pinned()).isFalse();
        });
    }

    @Test
    @DisplayName("extraction adds new terms, never changes existing ones, and covers the pages")
    void extendsWithoutChangingExistingTerms() {
        repository.save(bookId, term("heart failure", "insuficiență cardiacă", true));
        when(extractor.propose(any())).thenReturn(List.of(
                new ProposedTerm("heart failure", "IC", TermKind.CONDITION),
                new ProposedTerm("pulmonary oedema", "edem pulmonar", TermKind.CONDITION)));

        int added = service.extend(bookId, RO, List.of(
                page(1, "Heart failure may cause pulmonary oedema."),
                page(2, "Pulmonary oedema is treated urgently.")), progress(false));

        assertThat(added).isEqualTo(1);
        assertThat(repository.target("heart failure")).isEqualTo("insuficiență cardiacă");
        assertThat(repository.target("pulmonary oedema")).isEqualTo("edem pulmonar");
        assertThat(repository.findBySource(bookId, RO, "pulmonary oedema").orElseThrow().occurrences())
                .isEqualTo(2);
        assertThat(repository.covered).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    @DisplayName("pages already scanned are never sent again")
    void skipsCoveredPages() {
        repository.markCovered(bookId, RO, List.of(1, 2));

        assertThat(service.extend(bookId, RO, List.of(page(1, "a"), page(2, "b")), progress(false)))
                .isZero();
        verify(extractor, never()).propose(any());
    }

    @Test
    @DisplayName("long ranges are scanned in chunks, one request each")
    void chunksLongRanges() {
        String longPage = "Hypertension ".repeat(GlossaryService.CHUNK_CHARS / 13 / 2 + 1);
        when(extractor.propose(any())).thenReturn(List.of());

        service.extend(bookId, RO, IntStream.rangeClosed(1, 4)
                .mapToObj(n -> page(n, longPage)).toList(), progress(false));

        verify(extractor, times(4)).propose(any());
        assertThat(repository.covered).containsExactlyInAnyOrder(1, 2, 3, 4);
    }

    @Test
    @DisplayName("a failed request leaves the pages unscanned and does not fail the translation")
    void failureIsNotFatal() {
        when(extractor.propose(any())).thenThrow(new IllegalStateException("overloaded"));

        assertThat(service.extend(bookId, RO, List.of(page(1, "Hypertension.")), progress(false)))
                .isZero();
        assertThat(repository.covered).isEmpty();
    }

    @Test
    @DisplayName("a reader's edit replaces the rendering and pins the term")
    void editPins() {
        when(textbooks.findById(bookId)).thenReturn(Optional.of(Textbook.justUploaded(
                bookId, "Cardiology", Optional.empty(), 1, 1, ContentHash.ofText("b"), new BlobRef("a/b"))));
        repository.save(bookId, term("heart failure", "IC", false));

        GlossaryTerm saved = service.save(bookId, RO, "Heart failure", "insuficiență cardiacă", null)
                .orElseThrow();

        assertThat(saved.targetTerm()).isEqualTo("insuficiență cardiacă");
        assertThat(saved.pinned()).isTrue();
        assertThat(saved.kind()).isEqualTo(TermKind.CONDITION);
        assertThat(repository.findAll(bookId, RO)).hasSize(1);
    }

    private static GlossaryTerm term(String source, String target, boolean pinned) {
        return new GlossaryTerm(null, source, target, RO, TermKind.CONDITION, 0, pinned);
    }

    private static PageLayout page(int pageNo, String text) {
        return new PageLayout(pageNo, 595, 842, PageKind.TEXT, List.of(new TextBlock(
                "p" + pageNo + "-b0", text, new BlockBox(50, 100, 400, 30),
                new FontInfo("Minion-Regular", 9.5f, false, false), 0, 0, TextRole.BODY)));
    }

    private static JobCoordinator.ProgressSink progress(boolean cancelled) {
        return new JobCoordinator.ProgressSink() {
            @Override public JobId jobId() { return JobId.newId(); }
            @Override public void total(int totalUnits) {}
            @Override public void advance(int completedUnits) {}
            @Override public void phase(String phase) {}
            @Override public boolean cancelled() { return cancelled; }
        };
    }

    /** Behaves like the JDBC repository: case-insensitive source terms, existing terms win. */
    private static final class InMemoryGlossary implements GlossaryRepository {
        private final List<GlossaryTerm> terms = new ArrayList<>();
        private final Set<Integer> covered = new HashSet<>();

        String target(String source) {
            return findBySource(null, RO, source).orElseThrow().targetTerm();
        }

        @Override
        public List<GlossaryTerm> findAll(TextbookId textbookId, LanguageTag language) {
            return List.copyOf(terms);
        }

        @Override
        public Optional<GlossaryTerm> findBySource(TextbookId id, LanguageTag language, String source) {
            return terms.stream().filter(t -> t.sourceTerm().equalsIgnoreCase(source.strip())).findFirst();
        }

        @Override
        public void addNew(TextbookId textbookId, List<GlossaryTerm> newTerms) {
            newTerms.forEach(term -> {
                if (findBySource(textbookId, term.targetLanguage(), term.sourceTerm()).isEmpty()) terms.add(term);
            });
        }

        @Override
        public GlossaryTerm save(TextbookId textbookId, GlossaryTerm term) {
            terms.removeIf(t -> t.sourceTerm().equalsIgnoreCase(term.sourceTerm()));
            terms.add(term);
            return term;
        }

        @Override
        public void addOccurrences(TextbookId textbookId, LanguageTag language, Map<String, Integer> counts) {
            counts.forEach((source, count) -> findBySource(textbookId, language, source).ifPresent(t -> {
                terms.remove(t);
                terms.add(new GlossaryTerm(t.id(), t.sourceTerm(), t.targetTerm(), t.targetLanguage(),
                        t.kind(), t.occurrences() + count, t.pinned()));
            }));
        }

        @Override
        public boolean delete(TextbookId textbookId, LanguageTag language, GlossaryTermId id) {
            return terms.removeIf(t -> t.id().equals(id));
        }

        @Override
        public Set<Integer> coveredPages(TextbookId textbookId, LanguageTag language, PageRange range) {
            return Set.copyOf(covered);
        }

        @Override
        public void markCovered(TextbookId textbookId, LanguageTag language, Collection<Integer> pages) {
            covered.addAll(pages);
        }
    }
}
