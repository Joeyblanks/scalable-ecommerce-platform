package io.github.joeyblanks.ecommerce.catalog.service;

import io.github.joeyblanks.ecommerce.catalog.dto.CategoryRequest;
import io.github.joeyblanks.ecommerce.catalog.dto.CategoryResponse;
import io.github.joeyblanks.ecommerce.catalog.dto.PageResponse;
import io.github.joeyblanks.ecommerce.catalog.model.Category;
import io.github.joeyblanks.ecommerce.catalog.repository.CategoryRepository;
import io.github.joeyblanks.ecommerce.catalog.repository.ProductRepository;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryService {

    public static final Set<String> SORT_FIELDS = Set.of("name", "createdAt");

    private final CategoryRepository categories;
    private final ProductRepository products;
    private final Clock clock;

    public CategoryService(CategoryRepository categories, ProductRepository products, Clock clock) {
        this.categories = categories;
        this.products = products;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<CategoryResponse> list(Pageable pageable) {
        return PageResponse.from(categories.findAll(pageable), CategoryResponse::from);
    }

    @Transactional(readOnly = true)
    public CategoryResponse get(UUID id) {
        return CategoryResponse.from(find(id));
    }

    @Transactional
    public CategoryResponse create(CategoryRequest request) {
        String name = request.name().trim();
        if (categories.existsByNameIgnoreCase(name)) {
            throw new ConflictException("A category named '" + name + "' already exists");
        }
        Category category = new Category(UUID.randomUUID(), name, blankToNull(request.description()), clock.instant());
        try {
            return CategoryResponse.from(categories.saveAndFlush(category));
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("A category named '" + name + "' already exists");
        }
    }

    @Transactional
    public CategoryResponse update(UUID id, CategoryRequest request) {
        Category category = find(id);
        String name = request.name().trim();
        if (categories.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw new ConflictException("A category named '" + name + "' already exists");
        }
        category.update(name, blankToNull(request.description()));
        try {
            return CategoryResponse.from(categories.saveAndFlush(category));
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("A category named '" + name + "' already exists");
        }
    }

    @Transactional
    public void delete(UUID id) {
        Category category = find(id);
        if (products.existsByCategoryId(id)) {
            throw new ConflictException("Category still has products; move or delete them first");
        }
        try {
            categories.delete(category);
            categories.flush();
        } catch (DataIntegrityViolationException e) {
            // A product was added concurrently; the foreign key protected us.
            throw new ConflictException("Category still has products; move or delete them first");
        }
    }

    private Category find(UUID id) {
        return categories.findById(id).orElseThrow(() -> new NotFoundException("Category not found: " + id));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
