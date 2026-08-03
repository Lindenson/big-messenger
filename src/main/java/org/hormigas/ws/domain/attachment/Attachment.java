package org.hormigas.ws.domain.attachment;

import java.time.Instant;

/**
 * An uploaded-file reference in a conversation (concept §10). The service stores the MinIO
 * {@code objectKey}, never a URL (ADR-010); presigned URLs are minted on demand at read time.
 * Lifecycle: {@code PENDING} (upload-url issued) → {@code CONFIRMED} (object verified in MinIO) →
 * {@code DELIVERED} (the attachment chat message was emitted into the pipeline). Splitting CONFIRMED
 * from DELIVERED makes confirm retry-safe: if the emit is rejected (ingress overloaded) the row stays
 * CONFIRMED-not-DELIVERED, so a retried confirm re-emits instead of silently skipping. An unconfirmed
 * row is reclaimed to {@code ORPHANED}. Framework-free domain record.
 */
public record Attachment(
        String id,
        String conversationId,
        String uploaderId,
        String objectKey,
        String fileName,
        String contentType,
        Long sizeBytes,
        AttachmentStatus status,
        Instant createdAt,
        Instant confirmedAt
) {
    /** Invariant: identity + object reference + lifecycle state are always present. sizeBytes is null
     *  until known (declared at request, verified at confirm) so it is not guarded here. */
    public Attachment {
        java.util.Objects.requireNonNull(id, "attachment id");
        java.util.Objects.requireNonNull(conversationId, "conversationId");
        java.util.Objects.requireNonNull(uploaderId, "uploaderId");
        java.util.Objects.requireNonNull(objectKey, "objectKey");
        java.util.Objects.requireNonNull(status, "status");
    }

    public enum AttachmentStatus { PENDING, CONFIRMED, DELIVERED, ORPHANED }
}
