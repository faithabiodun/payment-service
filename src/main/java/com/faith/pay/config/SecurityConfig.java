package com.faith.pay.config;

import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * Small deployment bootstrap only. A real customer identity service should replace
     * these environment-configured accounts before customer-specific authorization is added.
     */
    @Bean
    UserDetailsService apiUsers(Environment environment, PasswordEncoder encoder) {
        List<UserDetails> users = new ArrayList<>();
        addUser(users, environment, encoder, "API_CUSTOMER_USERNAME", "API_CUSTOMER_PASSWORD", "CUSTOMER");
        addUser(users, environment, encoder, "API_ADMIN_USERNAME", "API_ADMIN_PASSWORD", "ADMIN");
        return new InMemoryUserDetailsManager(users);
    }

    private void addUser(List<UserDetails> users, Environment environment, PasswordEncoder encoder,
                         String usernameKey, String passwordKey, String role) {
        String username = environment.getProperty(usernameKey, "").trim();
        String password = environment.getProperty(passwordKey, "");
        if (username.isEmpty() && password.isEmpty()) return;
        if (username.isEmpty() || password.isBlank()) {
            throw new IllegalStateException(usernameKey + " and " + passwordKey + " must both be configured");
        }
        users.add(User.withUsername(username).password(encoder.encode(password)).roles(role).build());
    }

    @Bean
    SecurityFilterChain security(
            HttpSecurity http
    ) throws Exception {

        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // These routes still verify provider-specific signatures before storing events.
                        .requestMatchers("/webhooks/paystack", "/webhooks/flutterwave", "/webhooks/stripe").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(basic -> {});

        return http.build();
    }
}
