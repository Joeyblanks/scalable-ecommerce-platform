package io.github.joeyblanks.ecommerce.payment.provider;

import java.math.BigDecimal;

/**
 * The simulated provider's deterministic behaviour. Orders carry no card details, so the outcome is driven
 * by the cents of the order total (like Stripe's test card numbers, but readable in a demo):
 * <ul>
 *   <li>{@code .13}: declined, CARD_DECLINED</li>
 *   <li>{@code .14}: declined, INSUFFICIENT_FUNDS</li>
 *   <li>{@code .15}: two transient provider errors, then success (shows the retry)</li>
 *   <li>anything else: success</li>
 * </ul>
 */
public final class SimulatedRules {

    public static final int TRANSIENT_FAILURES_FOR_RETRY_DEMO = 2;

    public enum Behaviour { SUCCEED, DECLINE_CARD, DECLINE_FUNDS, FLAKY_THEN_SUCCEED }

    private SimulatedRules() {
    }

    public static Behaviour behaviourFor(BigDecimal amount) {
        int cents = amount.setScale(2, java.math.RoundingMode.HALF_UP).unscaledValue().mod(java.math.BigInteger.valueOf(100)).intValue();
        return switch (cents) {
            case 13 -> Behaviour.DECLINE_CARD;
            case 14 -> Behaviour.DECLINE_FUNDS;
            case 15 -> Behaviour.FLAKY_THEN_SUCCEED;
            default -> Behaviour.SUCCEED;
        };
    }
}
