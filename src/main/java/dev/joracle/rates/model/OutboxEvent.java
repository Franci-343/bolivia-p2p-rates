package dev.joracle.rates.model;

/**
 * Represents a transactional outbox event for asynchronous synchronization to Oracle.
 *
 * @param seqId        Monotonic local sequence ID ensuring deterministic ordering
 * @param eventId      Globally unique and stable identifier (sourceInstanceId:eventType:aggregateId:opId)
 * @param eventType    Type of event: RATE_SNAPSHOT, API_CLIENT_UPSERT, API_CLIENT_DELETE
 * @param aggregateId  Business identifier (e.g., cycleId for snapshots, clientId for clients)
 * @param payloadJson  JSON serialized event data
 * @param status       PENDING, SENT, or FAILED
 * @param retryCount   Number of failed synchronization attempts
 * @param lastError    Last recorded error message if failed
 * @param createdAt    Creation timestamp (ISO-8601 UTC)
 * @param processedAt  Completion timestamp (ISO-8601 UTC)
 */
public record OutboxEvent(
        long seqId,
        String eventId,
        String eventType,
        String aggregateId,
        String payloadJson,
        String status,
        int retryCount,
        String lastError,
        String createdAt,
        String processedAt
) {
    public static final String TYPE_RATE_SNAPSHOT = "RATE_SNAPSHOT";
    public static final String TYPE_API_CLIENT_UPSERT = "API_CLIENT_UPSERT";
    public static final String TYPE_API_CLIENT_DELETE = "API_CLIENT_DELETE";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FAILED = "FAILED";
}
