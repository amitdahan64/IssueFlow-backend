package com.att.tdp.issueflow.attachment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
@ConfigurationProperties(prefix = "issueflow.attachments")
public class AttachmentProperties {

    /** Mirrors {@code spring.servlet.multipart.max-file-size} for service-level checks. */
    private long maxBytes = 10_485_760L;

    /** Whitelist per spec 3.3. */
    private List<String> allowedContentTypes = List.of(
            "image/png", "image/jpeg", "application/pdf", "text/plain");

    public long getMaxBytes() { return maxBytes; }
    public void setMaxBytes(long maxBytes) { this.maxBytes = maxBytes; }

    public List<String> getAllowedContentTypes() { return allowedContentTypes; }
    public void setAllowedContentTypes(List<String> allowedContentTypes) {
        this.allowedContentTypes = allowedContentTypes;
    }

    public Set<String> allowedContentTypeSet() {
        return Set.copyOf(allowedContentTypes);
    }
}
