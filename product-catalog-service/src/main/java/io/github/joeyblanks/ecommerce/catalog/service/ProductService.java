package io.github.joeyblanks.ecommerce.catalog.service;

import io.github.joeyblanks.ecommerce.catalog.dto.PageResponse;
import io.github.joeyblanks.ecommerce.catalog.dto.ProductRequest;
import io.github.joeyblanks.ecommerce.catalog.dto.ProductResponse;
import io.github.joeyblanks.ecommerce.catalog.model.Category;
import io.github.joeyblanks.ecommerce.catalog.model.Product;
import io.github.joeyblanks.ecommerce.catalog.repository.CategoryRepository;
import io.github.joeyblanks.ecommerce.catalog.repository.ProductRepository;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {

    public static final Set<String> SORT_FIELDS = Set.of("name", "price", "sku", "stockQuantity", "createdAt");

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final Clock clock;

    public ProductService(ProductRepository products, CategoryRepository categories, Clock clock) {
        this.products = products;
        this.categories = categories;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> list(UUID categoryId, String query, Pageable pageable) {
        return PageResponse.from(products.findAll(filter(categoryId, query), pageable), ProductResponse::from);
    }

    @Transactional(readOnly = true)
    public ProductResponse get(UUID id) {
        return ProductResponse.from(find(id));
    }

    @Transactional
    public ProductResponse create(ProductRequest request) {
        String sku = normalizeSku(request.sku());
        if (products.existsBySku(sku)) {
            throw skuTaken(sku);
        }
        Product product = new Product(UUID.randomUUID(), sku, request.name().trim(), blankToNull(request.description()),
                money(request.price()), request.stockQuantity(), category(request), clock.instant());
        try {
            return ProductResponse.from(products.saveAndFlush(product));
        } catch (DataIntegrityViolationException e) {
            throw skuTaken(sku);
        }
    }

    @Transactional
    public ProductResponse update(UUID id, ProductRequest request) {
        Product product = find(id);
        String sku = normalizeSku(request.sku());
        if (products.existsBySkuAndIdNot(sku, id)) {
            throw skuTaken(sku);
        }
        product.update(sku, request.name().trim(), blankToNull(request.description()), money(request.price()),
                request.stockQuantity(), category(request), clock.instant());
        try {
            return ProductResponse.from(products.saveAndFlush(product));
        } catch (DataIntegrityViolationException e) {
            throw skuTaken(sku);
        }
    }

    @Transactional
    public void delete(UUID id) {
        products.delete(find(id));
    }

    private Product find(UUID id) {
        return products.findById(id).orElseThrow(() -> new NotFoundException("Product not found: " + id));
    }

    /** A bad reference inside the request body is a client error (400), not a missing resource. */
    private Category category(ProductRequest request) {
        return categories.findById(request.categoryId())
                .orElseThrow(() -> new InvalidRequestException("categoryId does not exist: " + request.categoryId()));
    }

    private static Specification<Product> filter(UUID categoryId, String query) {
        return (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (categoryId != null) {
                predicates.add(cb.equal(root.get("category").get("id"), categoryId));
            }
            if (query != null && !query.isBlank()) {
                String escaped = query.trim().toLowerCase(Locale.ROOT)
                        .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
                predicates.add(cb.like(cb.lower(root.get("name")), "%" + escaped + "%", '\\'));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static String normalizeSku(String sku) {
        return sku.trim().toUpperCase(Locale.ROOT);
    }

    private static BigDecimal money(BigDecimal price) {
        return price.setScale(2, java.math.RoundingMode.UNNECESSARY);
    }

    private static ConflictException skuTaken(String sku) {
        return new ConflictException("A product with SKU '" + sku + "' already exists");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
