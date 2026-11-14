package io.github.joeyblanks.ecommerce.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.joeyblanks.ecommerce.catalog.dto.CategoryRequest;
import io.github.joeyblanks.ecommerce.catalog.model.Category;
import io.github.joeyblanks.ecommerce.catalog.repository.CategoryRepository;
import io.github.joeyblanks.ecommerce.catalog.repository.ProductRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock CategoryRepository categories;
    @Mock ProductRepository products;
    final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    CategoryService service;

    @BeforeEach
    void setUp() {
        service = new CategoryService(categories, products, clock);
    }

    private Category existing() {
        return new Category(UUID.randomUUID(), "Books", "Paper", clock.instant());
    }

    @Test
    void createTrimsNameAndStoresIt() {
        when(categories.existsByNameIgnoreCase("Books")).thenReturn(false);
        when(categories.saveAndFlush(any(Category.class))).thenAnswer(i -> i.getArgument(0));

        var response = service.create(new CategoryRequest("  Books ", "   "));

        ArgumentCaptor<Category> saved = ArgumentCaptor.forClass(Category.class);
        verify(categories).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("Books");
        assertThat(saved.getValue().getDescription()).isNull();
        assertThat(response.createdAt()).isEqualTo(clock.instant());
    }

    @Test
    void createRejectsDuplicateNameIgnoringCase() {
        when(categories.existsByNameIgnoreCase("books")).thenReturn(true);

        assertThatThrownBy(() -> service.create(new CategoryRequest("books", null))).isInstanceOf(ConflictException.class);
        verify(categories, never()).saveAndFlush(any());
    }

    @Test
    void createMapsRaceToConflict() {
        when(categories.existsByNameIgnoreCase("Books")).thenReturn(false);
        when(categories.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("unique"));

        assertThatThrownBy(() -> service.create(new CategoryRequest("Books", null))).isInstanceOf(ConflictException.class);
    }

    @Test
    void updateChangesFieldsAndAllowsKeepingOwnName() {
        Category c = existing();
        when(categories.findById(c.getId())).thenReturn(Optional.of(c));
        when(categories.existsByNameIgnoreCaseAndIdNot("Books", c.getId())).thenReturn(false);
        when(categories.saveAndFlush(c)).thenReturn(c);

        var response = service.update(c.getId(), new CategoryRequest("Books", "New text"));

        assertThat(response.description()).isEqualTo("New text");
    }

    @Test
    void updateRejectsNameOfAnotherCategoryAndUnknownId() {
        Category c = existing();
        when(categories.findById(c.getId())).thenReturn(Optional.of(c));
        when(categories.existsByNameIgnoreCaseAndIdNot("Toys", c.getId())).thenReturn(true);
        UUID missing = UUID.randomUUID();
        when(categories.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(c.getId(), new CategoryRequest("Toys", null)))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.update(missing, new CategoryRequest("Toys", null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteRefusesWhenCategoryHasProducts() {
        Category c = existing();
        when(categories.findById(c.getId())).thenReturn(Optional.of(c));
        when(products.existsByCategoryId(c.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.delete(c.getId())).isInstanceOf(ConflictException.class);
        verify(categories, never()).delete(any());
    }

    @Test
    void deleteRemovesEmptyCategoryAndRejectsUnknown() {
        Category c = existing();
        when(categories.findById(c.getId())).thenReturn(Optional.of(c));
        when(products.existsByCategoryId(c.getId())).thenReturn(false);
        UUID missing = UUID.randomUUID();
        when(categories.findById(missing)).thenReturn(Optional.empty());

        service.delete(c.getId());

        verify(categories).delete(c);
        assertThatThrownBy(() -> service.delete(missing)).isInstanceOf(NotFoundException.class);
    }
}
