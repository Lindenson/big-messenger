package org.hormigas.ws.domain.attachment;

import org.hormigas.ws.domain.storage.PresignedUrl;

/** Phase-1 result detail: the new attachment id, its object key, and the presigned PUT URL. */
public record UploadTicket(String attachmentId, String objectKey, PresignedUrl upload) {
    public UploadTicket {
        java.util.Objects.requireNonNull(attachmentId, "attachmentId");
        java.util.Objects.requireNonNull(objectKey, "objectKey");
        java.util.Objects.requireNonNull(upload, "upload url");
    }
}
