package com.medlearn.library.application;

import com.medlearn.library.application.TextbookCatalogService.DeleteOutcome;
import com.medlearn.library.domain.Textbook;
import com.medlearn.library.domain.port.ChapterRepository;
import com.medlearn.library.domain.port.TextbookDependents;
import com.medlearn.library.domain.port.TextbookRepository;
import com.medlearn.shared.BlobRef;
import com.medlearn.shared.ContentHash;
import com.medlearn.shared.DomainError;
import com.medlearn.shared.TextbookId;
import com.medlearn.shared.port.BlobStorePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TextbookCatalogServiceDeleteTest {

    private final TextbookRepository textbooks = mock(TextbookRepository.class);
    private final BlobStorePort blobStore = mock(BlobStorePort.class);
    private final TextbookDependents dependents = mock(TextbookDependents.class);
    private final TextbookCatalogService catalog = new TextbookCatalogService(
            textbooks, mock(ChapterRepository.class), blobStore, List.of(dependents));

    private final TextbookId id = TextbookId.newId();
    private final BlobRef original = new BlobRef("ab/original.pdf");

    @Test
    @DisplayName("deleting clears what other contexts derived first, then the row, then the original")
    void deletesDependentsBeforeTheTextbook() {
        when(textbooks.findById(id)).thenReturn(Optional.of(book()));

        assertThat(catalog.delete(id)).isInstanceOf(DeleteOutcome.Deleted.class);

        var order = inOrder(dependents, textbooks, blobStore);
        order.verify(dependents).remove(id);
        order.verify(textbooks).delete(id);
        order.verify(blobStore).delete(original);
    }

    @Test
    @DisplayName("a textbook with a running job is not deleted, and nothing is removed")
    void refusesWhileBusy() {
        when(textbooks.findById(id)).thenReturn(Optional.of(book()));
        when(dependents.busy(id)).thenReturn(true);

        assertThat(catalog.delete(id)).isEqualTo(
                new DeleteOutcome.Rejected(new DomainError.TextbookBusy(id)));
        verify(dependents, never()).remove(any());
        verify(textbooks, never()).delete(any());
        verify(blobStore, never()).delete(any());
    }

    @Test
    @DisplayName("an unknown textbook is reported, not silently accepted")
    void reportsUnknownTextbook() {
        when(textbooks.findById(id)).thenReturn(Optional.empty());

        assertThat(catalog.delete(id)).isEqualTo(
                new DeleteOutcome.Rejected(new DomainError.TextbookNotFound(id)));
        verify(dependents, never()).remove(any());
    }

    private Textbook book() {
        return Textbook.justUploaded(id, "Neurology", Optional.empty(), 10, 100,
                ContentHash.ofText("neuro"), original);
    }
}
