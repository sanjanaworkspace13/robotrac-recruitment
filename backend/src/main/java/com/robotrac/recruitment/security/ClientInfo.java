package com.robotrac.recruitment.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Client address used for rate limiting and audit. Uses the TCP peer address. If you deploy behind a
 * trusted reverse proxy, set server.forward-headers-strategy=native so Tomcat resolves X-Forwarded-For
 * safely (never trust that header directly).
 */
public final class ClientInfo {

    private ClientInfo() {
    }

    public static String ip(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        return ip == null ? "unknown" : (ip.length() > 64 ? ip.substring(0, 64) : ip);
    }
}
