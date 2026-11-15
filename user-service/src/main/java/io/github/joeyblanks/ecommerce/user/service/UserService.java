package io.github.joeyblanks.ecommerce.user.service;

import io.github.joeyblanks.ecommerce.user.dto.LoginRequest;
import io.github.joeyblanks.ecommerce.user.dto.ContactResponse;
import io.github.joeyblanks.ecommerce.user.ratelimit.LoginThrottle;
import io.github.joeyblanks.ecommerce.user.dto.ProfileResponse;
import io.github.joeyblanks.ecommerce.user.dto.RegisterRequest;
import io.github.joeyblanks.ecommerce.user.dto.TokenResponse;
import io.github.joeyblanks.ecommerce.user.model.Role;
import io.github.joeyblanks.ecommerce.user.model.User;
import io.github.joeyblanks.ecommerce.user.repository.UserRepository;
import io.github.joeyblanks.ecommerce.user.security.JwtService;
import java.time.Clock;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final Clock clock;
    private final LoginThrottle loginThrottle;
    /** Compared against when the email is unknown, so unknown/known emails take similar time. */
    private final String dummyHash;

    public UserService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService, Clock clock,
                       LoginThrottle loginThrottle) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.clock = clock;
        this.loginThrottle = loginThrottle;
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
    }

    @Transactional
    public ProfileResponse register(RegisterRequest request) {
        String email = normalize(request.email());
        if (users.existsByEmail(email)) {
            throw new EmailAlreadyExistsException(email);
        }
        User user = new User(UUID.randomUUID(), email, passwordEncoder.encode(request.password()),
                request.fullName().trim(), Role.CUSTOMER, clock.instant());
        try {
            // saveAndFlush so a concurrent duplicate surfaces here as a unique-constraint violation.
            return ProfileResponse.from(users.saveAndFlush(user));
        } catch (DataIntegrityViolationException e) {
            throw new EmailAlreadyExistsException(email);
        }
    }

    /**
     * Creates an ADMIN account if no user with this email exists. Called only by the startup
     * bootstrap; there is deliberately no endpoint for it. An existing account is never modified
     * or promoted.
     *
     * @return true if the admin was created, false if the email was already taken
     */
    @Transactional
    public boolean createAdminIfAbsent(String email, String rawPassword, String fullName) {
        String normalized = normalize(email);
        if (users.existsByEmail(normalized)) {
            return false;
        }
        try {
            users.saveAndFlush(new User(UUID.randomUUID(), normalized, passwordEncoder.encode(rawPassword),
                    fullName, Role.ADMIN, clock.instant()));
            return true;
        } catch (DataIntegrityViolationException e) {
            return false; // created concurrently by another instance
        }
    }

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        String email = normalize(request.email());
        // Before any lookup or hashing, and for unknown emails too, so the answer reveals nothing about accounts.
        loginThrottle.check(email);
        User user = users.findByEmail(email).orElse(null);
        if (user == null) {
            passwordEncoder.matches(request.password(), dummyHash);
            loginThrottle.recordFailure(email);
            throw new InvalidCredentialsException();
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            loginThrottle.recordFailure(email);
            throw new InvalidCredentialsException();
        }
        loginThrottle.recordSuccess(email);
        return jwtService.issueToken(user);
    }

    @Transactional(readOnly = true)
    public ProfileResponse getProfile(UUID userId) {
        return users.findById(userId).map(ProfileResponse::from)
                .orElseThrow(() -> new UserNotFoundException(userId));
    }

    /** Only what a notification needs: the address and the name. Used by the internal endpoint. */
    @Transactional(readOnly = true)
    public ContactResponse getContact(UUID userId) {
        return users.findById(userId).map(u -> new ContactResponse(u.getId(), u.getEmail(), u.getFullName()))
                .orElseThrow(() -> new UserNotFoundException(userId));
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
