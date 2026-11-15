package io.github.joeyblanks.ecommerce.payment.provider;

import static io.github.joeyblanks.ecommerce.payment.provider.SimulatedRules.Behaviour.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SimulatedRulesTest {

    @Test
    void theCentsOfTheTotalDecideTheOutcome() {
        assertThat(SimulatedRules.behaviourFor(new BigDecimal("59.80"))).isEqualTo(SUCCEED);
        assertThat(SimulatedRules.behaviourFor(new BigDecimal("59.13"))).isEqualTo(DECLINE_CARD);
        assertThat(SimulatedRules.behaviourFor(new BigDecimal("0.14"))).isEqualTo(DECLINE_FUNDS);
        assertThat(SimulatedRules.behaviourFor(new BigDecimal("1000.15"))).isEqualTo(FLAKY_THEN_SUCCEED);
    }

    @Test
    void scaleDoesNotMatter() {
        assertThat(SimulatedRules.behaviourFor(new BigDecimal("10.130"))).isEqualTo(DECLINE_CARD);
        assertThat(SimulatedRules.behaviourFor(new BigDecimal("13"))).isEqualTo(SUCCEED); // 13.00, not .13
        assertThat(SimulatedRules.behaviourFor(BigDecimal.ZERO)).isEqualTo(SUCCEED);
    }
}
