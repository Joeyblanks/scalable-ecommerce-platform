package io.github.joeyblanks.ecommerce.payment.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.joeyblanks.ecommerce.payment.config.SecurityConfig;
import io.github.joeyblanks.ecommerce.payment.payment.Payment;
import io.github.joeyblanks.ecommerce.payment.payment.PaymentRepository;
import io.github.joeyblanks.ecommerce.payment.payment.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Web slice: real security chain and error mapping, mocked repository. */
@WebMvcTest(PaymentController.class)
@Import(SecurityConfig.class)
@EnableConfigurationProperties(io.github.joeyblanks.ecommerce.payment.config.AppProperties.Security.class)
@TestPropertySource(properties = {"app.security.jwk-set-uri=http://localhost:1/jwks.json", "app.security.issuer=user-service"})
class PaymentControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean PaymentRepository payments;

    private static final UUID USER = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();

    private static RequestPostProcessor user() {
        return jwt().jwt(j -> j.subject(USER.toString()).claim("roles", List.of("CUSTOMER")));
    }

    private static Payment payment(PaymentStatus status, String reason) {
        return new Payment(UUID.randomUUID(), ORDER, USER, new BigDecimal("59.8"), "EUR", status, false, reason,
                "sim_ch_secret", null, 1, 0, Instant.EPOCH, Instant.EPOCH);
    }

    @Test
    void showsTheCallersPaymentWithoutInternals() throws Exception {
        when(payments.findForUser(ORDER, USER)).thenReturn(Optional.of(payment(PaymentStatus.SUCCEEDED, null)));
        mvc.perform(get("/api/payments/orders/" + ORDER).with(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(ORDER.toString()))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.amount").value("59.80"))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.providerRef").doesNotExist())
                .andExpect(jsonPath("$.failureReason").doesNotExist());
    }

    @Test
    void showsTheReasonOnlyForFailedPayments() throws Exception {
        when(payments.findForUser(ORDER, USER)).thenReturn(Optional.of(payment(PaymentStatus.FAILED, "CARD_DECLINED")));
        mvc.perform(get("/api/payments/orders/" + ORDER).with(user()))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("CARD_DECLINED"));
    }

    @Test
    void unknownOrOtherUsersOrderIs404() throws Exception {
        when(payments.findForUser(any(), any())).thenReturn(Optional.empty());
        mvc.perform(get("/api/payments/orders/" + ORDER).with(user())).andExpect(status().isNotFound());
    }

    @Test
    void theQueryIsScopedToTheTokensSubject() throws Exception {
        UUID other = UUID.randomUUID();
        when(payments.findForUser(ORDER, other)).thenReturn(Optional.empty());
        mvc.perform(get("/api/payments/orders/" + ORDER).with(jwt().jwt(j -> j.subject(other.toString()))))
                .andExpect(status().isNotFound());
    }

    @Test
    void serviceTokensAreRefused() throws Exception {
        mvc.perform(get("/api/payments/orders/" + ORDER)
                        .with(jwt().jwt(j -> j.subject("order-service").claim("client_id", "order-service"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(payments);
    }

    @Test
    void anonymousIsUnauthorizedAndABadSubjectToo() throws Exception {
        mvc.perform(get("/api/payments/orders/" + ORDER)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/payments/orders/" + ORDER).with(jwt().jwt(j -> j.subject("not-a-uuid"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/payments/orders/not-a-uuid").with(user())).andExpect(status().isBadRequest());
        mvc.perform(get("/actuator/info").with(user())).andExpect(status().isForbidden());
    }
}
