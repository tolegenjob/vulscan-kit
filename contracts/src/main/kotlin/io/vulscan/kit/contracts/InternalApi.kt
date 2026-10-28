package io.vulscan.kit.contracts

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import java.util.UUID

/** POST /internal/v1/jobs/{jobId}/claim */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ClaimRequest(
    val attempt: Int,
    val workerId: String,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ClaimResponse(
    val leaseExpiresAt: Instant,
    val heartbeatIntervalSec: Int,
)

/** POST /internal/v1/jobs/{jobId}/heartbeat */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class HeartbeatRequest(
    val attempt: Int,
    val requestsSent: Int,
    val endpointsDone: Int,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class HeartbeatResponse(
    val cancel: Boolean,
    val leaseExpiresAt: Instant,
)

/** Элемент ответа GET /internal/v1/jobs/{jobId}/identities (содержит секреты, не логировать). */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class IdentityCredential(
    val identityId: UUID,
    val label: String,
    val role: String,
    val auth: AuthSpec,
) {
    override fun toString(): String = "IdentityCredential(identityId=$identityId, label=$label, role=$role, auth=$auth)"
}

/**
 * Способ аутентификации. [config] — несекретные параметры (URL логина, имена полей, JSONPath токена),
 * [secret] — секретные значения (пароль, токен, cookie).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AuthSpec(
    val type: AuthType,
    val config: Map<String, String> = emptyMap(),
    val secret: Map<String, String> = emptyMap(),
) {
    override fun toString(): String = "AuthSpec(type=$type, config=$config, secret=[REDACTED])"
}

/** Единый формат ошибок HTTP для /api/v1 и /internal/v1. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiError(
    val timestamp: Instant,
    val status: Int,
    val code: String,
    val message: String,
    val correlationId: UUID? = null,
    val fieldErrors: List<FieldError> = emptyList(),
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class FieldError(
    val field: String,
    val code: String,
    val message: String,
)
