package io.github.joeyblanks.ecommerce.catalog.service;

import java.util.Locale;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Builds a bounded, whitelisted, deterministic Pageable. Sorting only on allowed fields avoids
 * leaking entity internals (and 500s from unknown properties); the trailing id sort keeps page
 * boundaries stable when the primary sort has ties.
 */
public final class PageRequests {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private PageRequests() {
    }

    /** @param sort "field" or "field,asc|desc"; null/blank means {@code defaultField} ascending */
    public static Pageable of(int page, int size, String sort, Set<String> allowedFields, String defaultField) {
        if (page < 0) {
            throw new InvalidRequestException("page must be 0 or greater");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new InvalidRequestException("size must be between 1 and " + MAX_SIZE);
        }
        String field = defaultField;
        Sort.Direction direction = Sort.Direction.ASC;
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",", -1);
            if (parts.length > 2) {
                throw new InvalidRequestException("sort must be 'field' or 'field,asc|desc'");
            }
            field = parts[0].trim();
            if (parts.length == 2) {
                direction = switch (parts[1].trim().toLowerCase(Locale.ROOT)) {
                    case "asc" -> Sort.Direction.ASC;
                    case "desc" -> Sort.Direction.DESC;
                    default -> throw new InvalidRequestException("sort direction must be asc or desc");
                };
            }
            if (!allowedFields.contains(field)) {
                throw new InvalidRequestException("cannot sort by '" + field + "'; allowed: " + allowedFields);
            }
        }
        return PageRequest.of(page, size, Sort.by(direction, field).and(Sort.by(Sort.Direction.ASC, "id")));
    }
}
