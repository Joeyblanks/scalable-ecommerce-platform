package io.github.joeyblanks.ecommerce.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.joeyblanks.ecommerce.user.dto.LoginRequest;
import io.github.joeyblanks.ecommerce.user.dto.ProfileResponse;
import io.github.joeyblanks.ecommerce.user.dto.RegisterRequest;
import io.github.joeyblanks.ecommerce.user.dto.TokenResponse;
import io.github.joeyblanks.ecommerce.user.model.Role;
import io.github.joeyblanks.ecommerce.user.model.User;
import io.github.joeyblanks.ecommerce.user.repository.UserRepository;
import io.github.joeyblanks.ecommerce.user.security.JwtService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock UserRepository users;
    @Mock JwtService jwtService;
    // Real encoder: we want to prove passwords are actually hashed, not mock the behaviour away.
    final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    UserService service;

    @BeforeEach
    void setUp() {
        service = new UserService(users, encoder, jwtService, clock);
    }

    private User existingUser(String password) {
        return new User(UUID.randomUUID(), "ann@example.com", encoder.encode(password), "Ann", Role.CUSTOMER, clock.instant());
    }

    @Test
    void registerNormalizesEmailAndStoresHashedPassword() {
        when(users.existsByEmail("ann@example.com")).thenReturn(false);
        when(users.saveAndFlush(any(User.class))).thenAnswer(i -> i.getArgument(0));

        ProfileResponse profile = service.register(new RegisterRequest("  Ann@Example.COM ", "password123", " Ann "));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("ann@example.com");
        assertThat(saved.getValue().getPasswordHash()).isNotEqualTo("password123");
        assertThat(encoder.matches("password123", saved.getValue().getPasswordHash())).isTrue();
        assertThat(profile.email()).isEqualTo("ann@example.com");
        assertThat(profile.fullName()).isEqualTo("Ann");
        assertThat(saved.getValue().getRole()).isEqualTo(Role.CUSTOMER);
        assertThat(profile.role()).isEqualTo(Role.CUSTOMER);
        assertThat(profile.createdAt()).isEqualTo(clock.instant());
    }

    @Test
    void registerRejectsDuplicateEmail() {
        when(users.existsByEmail("ann@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.register(new RegisterRequest("ann@example.com", "password123", "Ann")))
                .isInstanceOf(EmailAlreadyExistsException.class);
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void registerMapsRaceConditionToEmailAlreadyExists() {
        when(users.existsByEmail("ann@example.com")).thenReturn(false);
        when(users.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("unique"));

        assertThatThrownBy(() -> service.register(new RegisterRequest("ann@example.com", "password123", "Ann")))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }

    @Test
    void loginReturnsTokenForCorrectCredentials() {
        User user = existingUser("password123");
        TokenResponse token = new TokenResponse("jwt", "Bearer", 3600);
        when(users.findByEmail("ann@example.com")).thenReturn(Optional.of(user));
        when(jwtService.issueToken(user)).thenReturn(token);

        assertThat(service.login(new LoginRequest("ANN@example.com", "password123"))).isEqualTo(token);
    }

    @Test
    void loginRejectsWrongPassword() {
        when(users.findByEmail("ann@example.com")).thenReturn(Optional.of(existingUser("password123")));

        assertThatThrownBy(() -> service.login(new LoginRequest("ann@example.com", "wrong-password")))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(jwtService, never()).issueToken(any());
    }

    @Test
    void loginRejectsUnknownEmailWithSameError() {
        when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(new LoginRequest("nobody@example.com", "password123")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void getProfileReturnsUser() {
        User user = existingUser("password123");
        when(users.findById(user.getId())).thenReturn(Optional.of(user));

        assertThat(service.getProfile(user.getId()).email()).isEqualTo("ann@example.com");
    }

    @Test
    void getProfileThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(users.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProfile(id)).isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void createAdminIfAbsentCreatesHashedAdmin() {
        when(users.existsByEmail("root@example.com")).thenReturn(false);

        assertThat(service.createAdminIfAbsent(" Root@Example.com ", "a-long-admin-password", "Administrator")).isTrue();

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo(Role.ADMIN);
        assertThat(saved.getValue().getEmail()).isEqualTo("root@example.com");
        assertThat(encoder.matches("a-long-admin-password", saved.getValue().getPasswordHash())).isTrue();
    }

    @Test
    void createAdminIfAbsentLeavesExistingAccountUntouched() {
        when(users.existsByEmail("root@example.com")).thenReturn(true);

        assertThat(service.createAdminIfAbsent("root@example.com", "a-long-admin-password", "Administrator")).isFalse();
        verify(users, never()).saveAndFlush(any());
    }
}
