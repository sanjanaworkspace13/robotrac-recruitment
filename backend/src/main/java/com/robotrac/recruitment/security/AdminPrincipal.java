package com.robotrac.recruitment.security;

import java.io.Serializable;

/** Identity of a logged-in HR/Admin user, stored in the server-side session. */
public record AdminPrincipal(Long adminId, String username, String fullName) implements Serializable {
}
