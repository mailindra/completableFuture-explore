package com.mailindra.microservice.controller;

import com.mailindra.microservice.model.User;
import com.mailindra.microservice.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import java.util.Optional;

public class UserController {
    private final UserService userService;
    private final ObjectMapper objectMapper;

    public UserController(UserService userService, ObjectMapper objectMapper) {
        this.userService = userService;
        this.objectMapper = objectMapper;
    }

    public void getUser(HttpServerExchange exchange) {
        exchange.dispatch(() -> {
            try {
                String userId = exchange.getQueryParameters().get("userId").getFirst();
                Long id = Long.parseLong(userId);

                userService.getUserById(id)
                        .thenAccept(userOpt -> {
                            try {
                                if (userOpt.isPresent()) {
                                    String json = objectMapper.writeValueAsString(userOpt.get());
                                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                                    exchange.setStatusCode(StatusCodes.OK);
                                    exchange.getResponseSender().send(json);
                                } else {
                                    exchange.setStatusCode(StatusCodes.NOT_FOUND);
                                    exchange.getResponseSender().send("{\"error\":\"User not found\"}");
                                }
                            } catch (Exception e) {
                                handleError(exchange, e);
                            }
                        })
                        .exceptionally(throwable -> {
                            handleError(exchange, throwable);
                            return null;
                        });
            } catch (Exception e) {
                handleError(exchange, e);
            }
        });
    }

    public void createUser(HttpServerExchange exchange) {
        exchange.getRequestReceiver().receiveFullString((ex, data) -> {
            try {
                User user = objectMapper.readValue(data, User.class);

                userService.createUser(user)
                        .thenAccept(createdUser -> {
                            try {
                                String json = objectMapper.writeValueAsString(createdUser);
                                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                                exchange.setStatusCode(StatusCodes.CREATED);
                                exchange.getResponseSender().send(json);
                            } catch (Exception e) {
                                handleError(exchange, e);
                            }
                        })
                        .exceptionally(throwable -> {
                            handleError(exchange, throwable);
                            return null;
                        });
            } catch (Exception e) {
                handleError(exchange, e);
            }
        });
    }

    public void updateUser(HttpServerExchange exchange) {
        exchange.getRequestReceiver().receiveFullString((ex, data) -> {
            try {
                String userId = exchange.getQueryParameters().get("userId").getFirst();
                Long id = Long.parseLong(userId);
                User userUpdate = objectMapper.readValue(data, User.class);

                userService.updateUser(id, userUpdate)
                        .thenAccept(updatedUser -> {
                            try {
                                String json = objectMapper.writeValueAsString(updatedUser);
                                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                                exchange.setStatusCode(StatusCodes.OK);
                                exchange.getResponseSender().send(json);
                            } catch (Exception e) {
                                handleError(exchange, e);
                            }
                        })
                        .exceptionally(throwable -> {
                            handleError(exchange, throwable);
                            return null;
                        });
            } catch (Exception e) {
                handleError(exchange, e);
            }
        });
    }

    public void deleteUser(HttpServerExchange exchange) {
        exchange.dispatch(() -> {
            try {
                String userId = exchange.getQueryParameters().get("userId").getFirst();
                Long id = Long.parseLong(userId);

                userService.deleteUser(id)
                        .thenAccept(deleted -> {
                            if (deleted) {
                                exchange.setStatusCode(StatusCodes.NO_CONTENT);
                                exchange.getResponseSender().send("");
                            } else {
                                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                                exchange.getResponseSender().send("{\"error\":\"User not found\"}");
                            }
                        })
                        .exceptionally(throwable -> {
                            handleError(exchange, throwable);
                            return null;
                        });
            } catch (Exception e) {
                handleError(exchange, e);
            }
        });
    }

    private void handleError(HttpServerExchange exchange, Throwable throwable) {
        try {
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
            String errorResponse = String.format(
                    "{\"error\":\"%s\",\"message\":\"%s\"}",
                    "internal_error",
                    throwable.getMessage() != null ? throwable.getMessage() : "Unknown error"
            );
            exchange.getResponseSender().send(errorResponse);
        } catch (Exception e) {
            System.err.println("Error handling error: " + e.getMessage());
        }
    }
}
