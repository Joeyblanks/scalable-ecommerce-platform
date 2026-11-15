package io.github.joeyblanks.ecommerce.payment.support;

import io.github.joeyblanks.ecommerce.payment.provider.SimulatedPaymentProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TestProviderConfig {

    @Bean
    @Primary
    ControlledProvider controlledProvider(SimulatedPaymentProvider simulated) {
        return new ControlledProvider(simulated);
    }
}
