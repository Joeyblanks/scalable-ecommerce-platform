package io.github.joeyblanks.ecommerce.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class PageRequestsTest {

    private static final Set<String> ALLOWED = Set.of("name", "price");

    @Test
    void defaultsToDefaultFieldAscendingWithIdTieBreaker() {
        Pageable p = PageRequests.of(0, 20, null, ALLOWED, "name");

        assertThat(p.getPageNumber()).isZero();
        assertThat(p.getPageSize()).isEqualTo(20);
        assertThat(p.getSort().stream().map(o -> o.getProperty() + ":" + o.getDirection()).toList())
                .containsExactly("name:ASC", "id:ASC");
    }

    @Test
    void parsesFieldAndDirection() {
        Pageable p = PageRequests.of(2, 10, " price , DESC ", ALLOWED, "name");

        assertThat(p.getSort().getOrderFor("price").getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(p.getSort().getOrderFor("id")).isNotNull();
    }

    @Test
    void rejectsUnknownSortFieldDirectionAndShape() {
        assertThatThrownBy(() -> PageRequests.of(0, 20, "password", ALLOWED, "name"))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("cannot sort by");
        assertThatThrownBy(() -> PageRequests.of(0, 20, "name,sideways", ALLOWED, "name"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> PageRequests.of(0, 20, "name,asc,extra", ALLOWED, "name"))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void enforcesBounds() {
        assertThatThrownBy(() -> PageRequests.of(-1, 20, null, ALLOWED, "name")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> PageRequests.of(0, 0, null, ALLOWED, "name")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> PageRequests.of(0, 101, null, ALLOWED, "name")).isInstanceOf(InvalidRequestException.class);
        assertThat(PageRequests.of(0, 100, null, ALLOWED, "name").getPageSize()).isEqualTo(100);
    }
}
