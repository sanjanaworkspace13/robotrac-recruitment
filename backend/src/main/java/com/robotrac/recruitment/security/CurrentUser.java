package com.robotrac.recruitment.security;

import com.robotrac.recruitment.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Reads the authenticated principal from the security context. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static CandidatePrincipal candidate() {
        Object p = principal();
        if (p instanceof CandidatePrincipal c) {
            return c;
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Please log in as a candidate.");
    }

    public static AdminPrincipal admin() {
        Object p = principal();
        if (p instanceof AdminPrincipal a) {
            return a;
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Please log in as HR/Admin.");
    }

    public static Object principal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : auth.getPrincipal();
    }
}
