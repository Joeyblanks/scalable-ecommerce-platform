package io.github.joeyblanks.ecommerce.payment.provider;

import com.stripe.exception.CardException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import io.github.joeyblanks.ecommerce.payment.config.AppProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Stripe, TEST MODE ONLY. Active when {@code PAYMENT_PROVIDER=stripe}, or when it is {@code auto} (default)
 * and {@code STRIPE_API_KEY} is set. A key that is not {@code sk_test_}/{@code rk_test_} stops the service
 * from starting.
 *
 * <p>Every call carries our idempotency key, so a retry after a timeout returns Stripe's original answer.
 * The SDK's own automatic retries are switched off: retrying is payment-service's job, with the same key,
 * under its own lease and backoff.
 *
 * <p>Orders carry no card details, so the customer's payment method is one of Stripe's documented TEST
 * payment methods, chosen by the cents of the total exactly like the simulated provider (.13 declined,
 * .14 insufficient funds, anything else succeeds). Real checkout would collect a payment method client-side.
 *
 * <p>Outcome mapping: only a succeeded PaymentIntent or a card decline (HTTP 402) is definitive. Anything
 * else (network trouble, 5xx, 429, a bad request or key, a PaymentIntent still processing) is Transient:
 * the outcome is unknown and is never recorded as a decline.
 */
@Component
@ConditionalOnExpression("'${app.payments.provider:auto}' == 'stripe' or "
        + "('${app.payments.provider:auto}' == 'auto' and !'${app.stripe.api-key:}'.isBlank())")
public class StripePaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(StripePaymentProvider.class);

    static final String PM_SUCCEED = "pm_card_visa";
    static final String PM_DECLINE = "pm_card_chargeDeclined";
    static final String PM_DECLINE_FUNDS = "pm_card_chargeDeclinedInsufficientFunds";

    private final String apiKey;
    private final String apiBase;

    public StripePaymentProvider(AppProperties.Stripe config) {
        this.apiKey = StripeKeys.requireTestKey(config.apiKey());
        this.apiBase = config.apiBase() == null || config.apiBase().isBlank() ? null : config.apiBase().trim();
        log.warn("Using the STRIPE provider in TEST MODE{}: test cards only, no real money moves.",
                apiBase == null ? "" : " against " + apiBase);
    }

    @Override
    public String name() {
        return "stripe-test";
    }

    @Override
    public ChargeResult charge(BigDecimal amount, String currency, String idempotencyKey) {
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(minorUnits(amount))
                .setCurrency(currency.toLowerCase(Locale.ROOT))
                .setPaymentMethod(testPaymentMethodFor(amount))
                .setConfirm(true)
                // Never ask for a redirect or a customer action: such a charge would be stuck forever.
                .setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                        .setEnabled(true)
                        .setAllowRedirects(PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.NEVER)
                        .build())
                .build();
        try {
            PaymentIntent intent = PaymentIntent.create(params, options(idempotencyKey));
            if ("succeeded".equals(intent.getStatus())) {
                return new ChargeResult.Succeeded(intent.getId());
            }
            // processing / requires_action / requires_capture ...: not money taken, not a decline.
            return new ChargeResult.Transient("PaymentIntent status " + intent.getStatus());
        } catch (CardException e) {
            return new ChargeResult.Declined(declineCode(e));
        } catch (StripeException e) {
            return new ChargeResult.Transient(describe(e));
        } catch (RuntimeException e) {
            return new ChargeResult.Transient(e.getClass().getSimpleName());
        }
    }

    @Override
    public RefundResult refund(String providerRef, String idempotencyKey) {
        try {
            Refund refund = Refund.create(RefundCreateParams.builder().setPaymentIntent(providerRef).build(),
                    options(idempotencyKey));
            if ("failed".equals(refund.getStatus()) || "canceled".equals(refund.getStatus())) {
                return new RefundResult.Transient("refund status " + refund.getStatus());
            }
            return new RefundResult.Refunded(refund.getId()); // succeeded or pending: Stripe owns it from here
        } catch (StripeException e) {
            return new RefundResult.Transient(describe(e));
        } catch (RuntimeException e) {
            return new RefundResult.Transient(e.getClass().getSimpleName());
        }
    }

    private RequestOptions options(String idempotencyKey) {
        RequestOptions.RequestOptionsBuilder b = RequestOptions.builder()
                .setApiKey(apiKey)
                .setIdempotencyKey(idempotencyKey)
                .setMaxNetworkRetries(0)
                .setConnectTimeout(3_000)
                .setReadTimeout(15_000);
        if (apiBase != null) {
            b.setBaseUrl(apiBase);
        }
        return b.build();
    }

    static long minorUnits(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
    }

    static String testPaymentMethodFor(BigDecimal amount) {
        return switch (SimulatedRules.behaviourFor(amount)) {
            case DECLINE_CARD -> PM_DECLINE;
            case DECLINE_FUNDS -> PM_DECLINE_FUNDS;
            default -> PM_SUCCEED;
        };
    }

    /** A stable code of ours, never Stripe's free text (which could carry card details). */
    static String declineCode(CardException e) {
        return "insufficient_funds".equals(e.getDeclineCode()) || "insufficient_funds".equals(e.getCode())
                ? "INSUFFICIENT_FUNDS" : "CARD_DECLINED";
    }

    private static String describe(StripeException e) {
        return e.getClass().getSimpleName() + (e.getStatusCode() != null ? " http " + e.getStatusCode() : "");
    }
}
