package com.cvijeticc.diffreview.api;

import com.cvijeticc.diffreview.api.error.ErrorCodes;
import com.cvijeticc.diffreview.api.error.ErrorEnvelope;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Replaces Spring's whitelabel/default error body. Anything that reaches the
 * container's error dispatch - a status set by a filter, a servlet-level
 * rejection, an exception no handler claimed - still leaves through the
 * contract's envelope rather than Spring's own JSON shape.
 *
 * <p>A direct GET /error is not an error at all, so it answers 404 instead of
 * inventing a status.
 */
@RestController
public class ApiErrorController implements ErrorController {

    @RequestMapping("/error")
    public ResponseEntity<Map<String, Object>> handle(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = attribute instanceof Integer code ? code : 404;
        if (status < 400) {
            status = 404;
        }
        return ResponseEntity.status(status)
                .body(ErrorEnvelope.of(ErrorCodes.forStatus(status), ErrorCodes.messageForStatus(status)));
    }
}
