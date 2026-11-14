package io.github.joeyblanks.ecommerce.user.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.joeyblanks.ecommerce.user.service.UserService;
import org.junit.jupiter.api.Test;

class AdminBootstrapRunnerTest {

    private final UserService userService = mock(UserService.class);

    private void run(String email, String password) {
        new AdminBootstrapRunner(new BootstrapAdminProperties(email, password), userService).run(null);
    }

    @Test
    void doesNothingWhenNotConfigured() {
        run("", "");
        run(null, null);
        verify(userService, never()).createAdminIfAbsent(anyString(), anyString(), anyString());
    }

    @Test
    void createsAdminWhenBothSet() {
        when(userService.createAdminIfAbsent(any(), any(), any())).thenReturn(true);

        run("root@example.com", "a-long-admin-password");

        verify(userService).createAdminIfAbsent("root@example.com", "a-long-admin-password", "Administrator");
    }

    @Test
    void secondRunIsHarmless() {
        when(userService.createAdminIfAbsent(any(), any(), any())).thenReturn(false);

        assertThatCode(() -> run("root@example.com", "a-long-admin-password")).doesNotThrowAnyException();
    }

    @Test
    void failsFastWhenOnlyOneValueIsSet() {
        assertThatThrownBy(() -> run("root@example.com", "")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> run("", "a-long-admin-password")).isInstanceOf(IllegalStateException.class);
        verify(userService, never()).createAdminIfAbsent(anyString(), anyString(), anyString());
    }

    @Test
    void rejectsWeakOrInvalidInputAndNeverEchoesPassword() {
        assertThatThrownBy(() -> run("root@example.com", "short")).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("short");
        assertThatThrownBy(() -> run("not-an-email", "a-long-admin-password"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void propertiesToStringHidesPassword() {
        org.assertj.core.api.Assertions.assertThat(new BootstrapAdminProperties("a@b.com", "supersecretvalue").toString())
                .doesNotContain("supersecretvalue");
    }
}
