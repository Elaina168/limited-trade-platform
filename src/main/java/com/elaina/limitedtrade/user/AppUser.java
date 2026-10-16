package com.elaina.limitedtrade.user;

public record AppUser(Long id, String username, String passwordHash, String role) {
}
