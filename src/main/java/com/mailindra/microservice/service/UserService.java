package com.mailindra.microservice.service;

import com.mailindra.microservice.client.ExternalServiceClient;
import com.mailindra.microservice.model.User;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class UserService {
    private final DatabaseService databaseService;
    private final ExternalServiceClient externalServiceClient;

    public UserService(DatabaseService databaseService, ExternalServiceClient externalServiceClient) {
        this.databaseService = databaseService;
        this.externalServiceClient = externalServiceClient;
    }

    public CompletableFuture<Optional<User>> getUserById(Long id) {
        return databaseService.findUserById(id);
    }

    public CompletableFuture<User> createUser(User user) {
        // Validate email with external service
        return externalServiceClient.validateUser(user.getEmail())
                .thenCompose(validation -> {
                    Boolean isValid = (Boolean) validation.get("valid");
                    if (!isValid) {
                        throw new RuntimeException("Email validation failed: " + validation.get("reason"));
                    }

                    // Save user to database
                    return databaseService.saveUser(user);
                })
                .thenCompose(savedUser -> {
                    // Send notification asynchronously (fire and forget)
                    externalServiceClient.notifyUserCreated(savedUser.getId(), savedUser.getEmail());
                    return CompletableFuture.completedFuture(savedUser);
                });
    }

    public CompletableFuture<User> updateUser(Long id, User userUpdate) {
        return databaseService.findUserById(id)
                .thenCompose(existingUser -> {
                    if (!existingUser.isPresent()) {
                        throw new RuntimeException("User not found: " + id);
                    }

                    User user = existingUser.get();
                    user.setUsername(userUpdate.getUsername());
                    user.setEmail(userUpdate.getEmail());
                    user.setFirstName(userUpdate.getFirstName());
                    user.setLastName(userUpdate.getLastName());

                    return databaseService.saveUser(user);
                });
    }

    public CompletableFuture<Boolean> deleteUser(Long id) {
        return databaseService.deleteUser(id);
    }
}
