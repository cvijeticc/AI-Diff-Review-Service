package com.cvijeticc.diffreview.auth;

import com.cvijeticc.diffreview.api.error.ErrorEnvelope;
import com.cvijeticc.diffreview.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * All /v1/* routes (every method) require an Authorization bearer token.
 * /health and /spec stay public. Comparison is constant-time.
 */
@Component
public class BearerAuthFilter extends OncePerRequestFilter {

    private final AppProperties props;
    private final ObjectMapper mapper;

    public BearerAuthFilter(AppProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/v1/") && !path.equals("/v1")) {
            chain.doFilter(request, response);
            return;
        }
        String header = request.getHeader("Authorization");
        String expected = "Bearer " + props.authToken();
        boolean ok = header != null && MessageDigest.isEqual(
                header.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(mapper.writeValueAsString(
                    ErrorEnvelope.of("unauthorized", "Missing or invalid bearer token")));
            return;
        }
        chain.doFilter(request, response);
    }
}
