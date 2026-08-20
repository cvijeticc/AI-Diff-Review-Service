package com.cvijeticc.diffreview.config;

import com.cvijeticc.diffreview.api.error.ErrorCodes;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;

/**
 * Tomcat rejects some requests before any servlet runs - a URI containing an
 * encoded slash, for instance - and renders its own HTML page for them. The
 * contract asks for the error envelope on every non-2xx, so those responses
 * have to be rewritten where they are actually produced, below Spring.
 *
 * <p>Instantiated by Tomcat via {@code Host.setErrorReportValveClass}, so it
 * needs a public no-arg constructor and cannot take collaborators; the JSON
 * is small enough to build by hand and this avoids depending on a Jackson
 * bean from inside the container's own error path.
 */
public class JsonErrorReportValve extends ErrorReportValve {

    @Override
    protected void report(Request request, Response response, Throwable throwable) {
        int status = response.getStatus();
        if (status < 400 || response.getContentWritten() > 0) {
            return; // nothing to report, or the body is already on the wire
        }
        try {
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(envelope(ErrorCodes.forStatus(status), ErrorCodes.messageForStatus(status)));
            response.getWriter().flush();
            response.finishResponse();
        } catch (Exception e) {
            // The client is gone or the response is already committed; there is
            // nothing useful left to do at this depth.
        }
    }

    private static String envelope(String code, String message) {
        return "{\"error\":{\"code\":\"" + escape(code) + "\",\"message\":\"" + escape(message) + "\"}}";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
