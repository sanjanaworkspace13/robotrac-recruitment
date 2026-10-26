package com.robotrac.recruitment.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robotrac.recruitment.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** JSON 401/403 responses for the API (instead of HTML error pages or login redirects). */
@Component
public class JsonSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public JsonSecurityHandlers(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        write(response, ErrorResponse.of(401, "UNAUTHENTICATED",
                "Your session has expired or you are not logged in. Please log in again.", request.getRequestURI()));
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException ex) throws IOException {
        if (ex instanceof CsrfException) {
            write(response, ErrorResponse.of(403, "CSRF_INVALID",
                    "Your security token expired. Please retry.", request.getRequestURI()));
            return;
        }
        write(response, ErrorResponse.of(403, "FORBIDDEN",
                "You do not have access to this resource.", request.getRequestURI()));
    }

    private void write(HttpServletResponse response, ErrorResponse body) throws IOException {
        response.setStatus(body.status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
