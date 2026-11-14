package io.github.joeyblanks.ecommerce.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.joeyblanks.ecommerce.catalog.dto.ProductRequest;
import io.github.joeyblanks.ecommerce.catalog.model.Category;
import io.github.joeyblanks.ecommerce.catalog.model.Product;
import io.github.joeyblanks.ecommerce.catalog.repository.CategoryRepository;
import io.github.joeyblanks.ecommerce.catalog.repository.ProductRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock ProductRepository products;
    @Mock CategoryRepository categories;
    final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    final Category books = new Category(UUID.randomUUID(), "Books", null, Instant.EPOCH);
    ProductService service;

    @BeforeEach
    void setUp() {
        service = new ProductService(products, categories, clock);
    }

    private ProductRequest request(String sku) {
        return request(sku, 0L); // a freshly built Product has version 0
    }

    private ProductRequest request(String sku, Long version) {
        return new ProductRequest(sku, "  Clean Code ", " A book ", new BigDecimal("29.9"), 5, books.getId(), version);
    }

    private Product existing() {
        return new Product(UUID.randomUUID(), "CC-1", "Clean Code", null, new BigDecimal("10.00"), 3, books, Instant.EPOCH);
    }

    @Test
    void createNormalizesSkuNameAndPrice() {
        when(products.existsBySku("CC-1")).thenReturn(false);
        when(categories.findById(books.getId())).thenReturn(Optional.of(books));
        when(products.saveAndFlush(any(Product.class))).thenAnswer(i -> i.getArgument(0));

        var response = service.create(request(" cc-1 "));

        ArgumentCaptor<Product> saved = ArgumentCaptor.forClass(Product.class);
        verify(products).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getSku()).isEqualTo("CC-1");
        assertThat(saved.getValue().getName()).isEqualTo("Clean Code");
        assertThat(saved.getValue().getPrice()).isEqualByComparingTo("29.90").hasScaleOf(2);
        assertThat(response.categoryName()).isEqualTo("Books");
        assertThat(response.createdAt()).isEqualTo(clock.instant());
    }

    @Test
    void createRejectsDuplicateSku() {
        when(products.existsBySku("CC-1")).thenReturn(true);

        assertThatThrownBy(() -> service.create(request("cc-1"))).isInstanceOf(ConflictException.class);
        verify(products, never()).saveAndFlush(any());
    }

    @Test
    void createMapsRaceToConflict() {
        when(products.existsBySku("CC-1")).thenReturn(false);
        when(categories.findById(books.getId())).thenReturn(Optional.of(books));
        when(products.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("unique"));

        assertThatThrownBy(() -> service.create(request("CC-1"))).isInstanceOf(ConflictException.class);
    }

    @Test
    void createWithUnknownCategoryIsABadRequest() {
        when(products.existsBySku("CC-1")).thenReturn(false);
        when(categories.findById(books.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request("CC-1"))).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void updateChangesStockAndTimestamp() {
        Product p = existing();
        when(products.findById(p.getId())).thenReturn(Optional.of(p));
        when(products.existsBySkuAndIdNot("CC-1", p.getId())).thenReturn(false);
        when(categories.findById(books.getId())).thenReturn(Optional.of(books));
        when(products.saveAndFlush(p)).thenReturn(p);

        var response = service.update(p.getId(), request("CC-1"));

        assertThat(response.stockQuantity()).isEqualTo(5);
        assertThat(response.updatedAt()).isEqualTo(clock.instant());
        assertThat(response.createdAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void updateRejectsSkuOfAnotherProductAndUnknownId() {
        Product p = existing();
        when(products.findById(p.getId())).thenReturn(Optional.of(p));
        when(products.existsBySkuAndIdNot("TAKEN", p.getId())).thenReturn(true);
        UUID missing = UUID.randomUUID();
        when(products.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(p.getId(), request("taken"))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.update(missing, request("CC-1"))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateRequiresTheVersionThatWasRead() {
        Product p = existing();
        when(products.findById(p.getId())).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> service.update(p.getId(), request("CC-1", null)))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("version is required");
        verify(products, never()).saveAndFlush(any());
    }

    @Test
    void updateWithAStaleVersionIsAConflictAndChangesNothing() {
        Product p = existing(); // version 0
        when(products.findById(p.getId())).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> service.update(p.getId(), request("CC-1", 3L)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("changed since you read it");
        verify(products, never()).saveAndFlush(any());
        assertThat(p.getStockQuantity()).isEqualTo(3);
    }

    @Test
    void deleteRemovesProductAndRejectsUnknown() {
        Product p = existing();
        when(products.findById(p.getId())).thenReturn(Optional.of(p));
        UUID missing = UUID.randomUUID();
        when(products.findById(missing)).thenReturn(Optional.empty());

        service.delete(p.getId());

        verify(products).delete(p);
        assertThatThrownBy(() -> service.delete(missing)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void getUnknownThrowsNotFound() {
        UUID missing = UUID.randomUUID();
        when(products.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(missing)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void listMapsPageMetadata() {
        var pageable = PageRequest.of(1, 2);
        when(products.findAll(any(Specification.class), org.mockito.ArgumentMatchers.eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(existing()), pageable, 3));

        var response = service.list(null, "clean", pageable);

        assertThat(response.items()).hasSize(1);
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(3);
        assertThat(response.totalPages()).isEqualTo(2);
    }
}
