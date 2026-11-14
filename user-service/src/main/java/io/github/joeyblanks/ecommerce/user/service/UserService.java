package io.github.joeyblanks.ecommerce.user.service;

import io.github.joeyblanks.ecommerce.user.dto.LoginRequest;
import io.github.joeyblanks.ecommerce.user.dto.ProfileResponse;
import io.github.joeyblanks.ecommerce.user.dto.RegisterRequest;
import io.github.joeyblanks.ecommerce.user.dto.TokenResponse;
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
    /** Compared against when the email is unknown, so unknown/known emails take similar time. */
    private final String dummyHash;

    public UserService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
    }

    @Transactional
    public ProfileResponse register(RegisterRequest request) {
        String email = normalize(request.email());
        if (users.existsByEmail(email)) {
            throw new EmailAlreadyExistsException(email);
        }
        User user = new User(UUID.randomUUID(), email, passwordEncoder.encode(request.password()),
                request.fullName().trim(), clock.instant());
        try {
            // saveAndFlush so a concurrent duplicate surfaces here as a unique-constraint violation.
            return ProfileResponse.from(users.saveAndFlush(user));
        } catch (DataIntegrityViolationException e) {
            throw new EmailAlreadyExistsException(email);
        }
    }

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        User user = users.findByEmail(normalize(request.email())).orElse(null);
        if (user == null) {
            passwordEncoder.matches(request.password(), dummyHash);
            throw new InvalidCredentialsException();
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        return jwtService.issueToken(user);
    }

    @Transactional(readOnly = true)
    public ProfileResponse getProfile(UUID userId) {
        return users.findById(userId).map(ProfileResponse::from)
                .orElseThrow(() -> new UserNotFoundException(userId));
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
