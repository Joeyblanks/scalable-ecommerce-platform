package io.github.joeyblanks.ecommerce.order.client;

/** A service we depend on (cart, catalog, token endpoint) could not give a trustworthy answer. */
public class DependencyUnavailableException extends RuntimeException {

    private final String dependency;

    public DependencyUnavailableException(String dependency, String message, Throwable cause) {
        super(message, cause);
        this.dependency = dependency;
    }

    public String dependency() {
        return dependency;
    }
}
