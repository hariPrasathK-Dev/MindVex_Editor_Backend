package ai.mindvex.backend.service;

import ai.mindvex.backend.dto.*;
import ai.mindvex.backend.entity.OtpType;
import ai.mindvex.backend.entity.PendingRegistration;
import ai.mindvex.backend.entity.User;
import ai.mindvex.backend.exception.ResourceNotFoundException;
import ai.mindvex.backend.exception.UnauthorizedException;
import ai.mindvex.backend.repository.PendingRegistrationRepository;
import ai.mindvex.backend.repository.UserRepository;
import ai.mindvex.backend.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

        private final UserRepository userRepository;
        private final PendingRegistrationRepository pendingRegistrationRepository;
        private final PasswordEncoder passwordEncoder;
        private final JwtService jwtService;
        private final AuthenticationManager authenticationManager;
        private final UserDetailsService userDetailsService;
        private final OtpService otpService;

        private static final int PENDING_REGISTRATION_EXPIRY_MINUTES = 30;

        /**
         * Step 1 of registration: Validate data, store pending registration, send OTP
         */
        @Transactional
        public OtpResponse initiateRegistration(RegisterRequest request) {
                // Check if email is already registered
                if (userRepository.existsByEmail(request.getEmail())) {
                        throw new IllegalArgumentException("Email already registered");
                }

                // Delete any existing pending registration for this email
                pendingRegistrationRepository.deleteByEmail(request.getEmail());

                // Create pending registration
                PendingRegistration pending = PendingRegistration.builder()
                                .email(request.getEmail())
                                .passwordHash(passwordEncoder.encode(request.getPassword()))
                                .fullName(request.getFullName())
                                .expiresAt(LocalDateTime.now().plusMinutes(PENDING_REGISTRATION_EXPIRY_MINUTES))
                                .build();

                pendingRegistrationRepository.save(pending);
                log.info("Pending registration created for: {}", request.getEmail());

                // Generate and send OTP
                otpService.generateAndSendOtp(request.getEmail(), OtpType.REGISTRATION);

                return OtpResponse.builder()
                                .success(true)
                                .message("Verification code sent to your email")
                                .requiresOtp(true)
                                .email(maskEmail(request.getEmail()))
                                .build();
        }

        /**
         * Step 1 of login: Validate credentials, send OTP
         */
        @Transactional
        public OtpResponse initiateLogin(LoginRequest request) {
                // Authenticate user (this will throw if invalid)
                authenticationManager.authenticate(
                                new UsernamePasswordAuthenticationToken(
                                                request.getEmail(),
                                                request.getPassword()));

                // User exists and password is correct, send OTP
                otpService.generateAndSendOtp(request.getEmail(), OtpType.LOGIN);

                return OtpResponse.builder()
                                .success(true)
                                .message("Verification code sent to your email")
                                .requiresOtp(true)
                                .email(maskEmail(request.getEmail()))
                                .build();
        }

        /**
         * Step 2: Verify OTP and complete authentication
         */
        @Transactional
        public AuthResponse verifyOtpAndAuthenticate(OtpVerifyRequest request) {
                OtpType otpType = "registration".equalsIgnoreCase(request.getType())
                                ? OtpType.REGISTRATION
                                : OtpType.LOGIN;

                // Verify OTP
                boolean isValid = otpService.verifyOtp(request.getEmail(), request.getOtp(), otpType);

                if (!isValid) {
                        throw new UnauthorizedException("Invalid or expired verification code");
                }

                User user;

                if (otpType == OtpType.REGISTRATION) {
                        // Complete registration
                        user = completeRegistration(request.getEmail());
                } else {
                        // Get existing user for login
                        user = userRepository.findByEmail(request.getEmail())
                                        .orElseThrow(() -> new UnauthorizedException("User not found"));
                }

                // Generate JWT tokens
                UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
                String jwtToken = jwtService.generateToken(userDetails);
                String refreshToken = jwtService.generateRefreshToken(userDetails);

                return AuthResponse.builder()
                                .token(jwtToken)
                                .refreshToken(refreshToken)
                                .user(mapToUserResponse(user))
                                .build();
        }

        /**
         * Complete the registration after OTP verification
         */
        @Transactional
        public User completeRegistration(String email) {
                PendingRegistration pending = pendingRegistrationRepository.findByEmail(email)
                                .orElseThrow(() -> new ResourceNotFoundException(
                                                "Registration session expired. Please register again."));

                // Check if pending registration has expired
                if (pending.getExpiresAt().isBefore(LocalDateTime.now())) {
                        pendingRegistrationRepository.delete(pending);
                        throw new UnauthorizedException("Registration session expired. Please register again.");
                }

                // Create the actual user
                User user = User.builder()
                                .email(pending.getEmail())
                                .passwordHash(pending.getPasswordHash())
                                .fullName(pending.getFullName())
                                .build();

                user = userRepository.save(user);
                log.info("User registration completed for: {}", email);

                // Clean up pending registration
                pendingRegistrationRepository.delete(pending);

                return user;
        }

        /**
         * Resend OTP for an email
         */
        @Transactional
        public OtpResponse resendOtp(ResendOtpRequest request) {
                OtpType otpType = "registration".equalsIgnoreCase(request.getType())
                                ? OtpType.REGISTRATION
                                : OtpType.LOGIN;

                // Validate that the request is legitimate
                if (otpType == OtpType.REGISTRATION) {
                        if (!pendingRegistrationRepository.existsByEmail(request.getEmail())) {
                                throw new ResourceNotFoundException("No pending registration found for this email");
                        }
                } else {
                        if (!userRepository.existsByEmail(request.getEmail())) {
                                throw new ResourceNotFoundException("No account found for this email");
                        }
                }

                // Generate and send new OTP
                otpService.generateAndSendOtp(request.getEmail(), otpType);

                return OtpResponse.builder()
                                .success(true)
                                .message("New verification code sent to your email")
                                .requiresOtp(true)
                                .email(maskEmail(request.getEmail()))
                                .build();
        }

        // Legacy methods for backward compatibility (can be removed later)

        @Transactional
        public AuthResponse register(RegisterRequest request) {
                if (userRepository.existsByEmail(request.getEmail())) {
                        throw new IllegalArgumentException("Email already registered");
                }

                User user = User.builder()
                                .email(request.getEmail())
                                .passwordHash(passwordEncoder.encode(request.getPassword()))
                                .fullName(request.getFullName())
                                .build();

                user = userRepository.save(user);

                UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
                String jwtToken = jwtService.generateToken(userDetails);
                String refreshToken = jwtService.generateRefreshToken(userDetails);

                return AuthResponse.builder()
                                .token(jwtToken)
                                .refreshToken(refreshToken)
                                .user(mapToUserResponse(user))
                                .build();
        }

        public AuthResponse login(LoginRequest request) {
                authenticationManager.authenticate(
                                new UsernamePasswordAuthenticationToken(
                                                request.getEmail(),
                                                request.getPassword()));

                User user = userRepository.findByEmail(request.getEmail())
                                .orElseThrow(() -> new UnauthorizedException("Invalid credentials"));

                UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
                String jwtToken = jwtService.generateToken(userDetails);
                String refreshToken = jwtService.generateRefreshToken(userDetails);

                return AuthResponse.builder()
                                .token(jwtToken)
                                .refreshToken(refreshToken)
                                .user(mapToUserResponse(user))
                                .build();
        }

        public UserResponse getCurrentUser(String email) {
                User user = userRepository.findByEmail(email)
                                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
                return mapToUserResponse(user);
        }

        private UserResponse mapToUserResponse(User user) {
                return UserResponse.builder()
                                .id(user.getId())
                                .email(user.getEmail())
                                .fullName(user.getFullName())
                                .createdAt(user.getCreatedAt())
                                .build();
        }

        /**
         * Mask email for display (e.g., "j***n@example.com")
         */
        private String maskEmail(String email) {
                int atIndex = email.indexOf('@');
                if (atIndex <= 2) {
                        return email.charAt(0) + "***" + email.substring(atIndex);
                }
                return email.charAt(0) + "***" + email.charAt(atIndex - 1) + email.substring(atIndex);
        }
}