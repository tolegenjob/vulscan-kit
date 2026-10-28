package io.vulscan.kit.contracts

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import java.util.UUID

/** Payload сообщения SCAN_JOB (Core -> модуль). */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ScanJobPayload(
    val jobId: UUID,
    val scanId: UUID,
    val moduleType: ModuleType,
    val attempt: Int,
    val deadlineAt: Instant,
    val policyId: UUID,
    val target: TargetRef,
    val scope: ScopeRules,
    val limits: ScanLimits,
    val endpoints: List<EndpointRef>,
    val moduleOptions: ModuleOptions,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class TargetRef(
    val targetId: UUID,
    val baseUrl: String,
    val allowedHosts: List<AllowedHost>,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AllowedHost(
    val host: String,
    val ports: List<Int>,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ScopeRules(
    val includePrefixes: List<String>,
    val excludePrefixes: List<String> = emptyList(),
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ScanLimits(
    val maxRequests: Int,
    val requestsPerSecond: Int,
    val maxConcurrency: Int,
    val httpTimeoutMs: Int,
    val maxResponseBytes: Int,
    val maxDurationSec: Int,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class EndpointRef(
    val endpointId: UUID,
    val method: HttpMethod,
    val url: String,
    val idempotent: Boolean,
    val parameters: List<ParameterRef> = emptyList(),
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ParameterRef(
    val name: String,
    val location: ParamLocation,
    val sampleValue: String? = null,
)

/** Должна быть заполнена ровно одна ветка, соответствующая moduleType. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ModuleOptions(
    val xss: XssOptions? = null,
    val sqli: SqliOptions? = null,
    val idor: IdorOptions? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class XssOptions(
    val reflected: Boolean = true,
    val stored: Boolean = false,
    val browserVerification: Boolean = false,
    val allowStore: Boolean = false,
    val observeEndpointIds: List<UUID> = emptyList(),
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SqliOptions(
    val errorBased: Boolean = true,
    val booleanBased: Boolean = true,
    val timeBased: Boolean = false,
    val dbmsHint: DbmsHint? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class IdorOptions(
    val mode: IdorMode = IdorMode.READ_ONLY,
    val identities: List<IdentityRef>,
    val resources: List<ResourceRef>,
    val sessionCheck: SessionCheck? = null,
    val discovery: List<DiscoveryRule> = emptyList(),
)

/** Ссылка на тестовую идентичность. Секретов не содержит. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class IdentityRef(
    val identityId: UUID,
    val label: String,
    val role: String,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ResourceRef(
    val resourceId: UUID,
    val endpointId: UUID,
    val idParameter: ParameterId,
    val idValue: String,
    val ownerIdentityId: UUID,
    val sensitivity: Sensitivity,
    /** JSONPath значений, уникальных для объекта владельца. */
    val ownerMarkers: List<String> = emptyList(),
    val access: List<AccessRuleRef>,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ParameterId(
    val name: String,
    val location: ParamLocation,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AccessRuleRef(
    val identityId: UUID,
    val expected: ExpectedAccess,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SessionCheck(
    val endpointId: UUID,
    val expectStatus: Int,
    /** JSONPath значения, которое должно совпасть с label идентичности. */
    val identityMarker: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class DiscoveryRule(
    val endpointId: UUID,
    val identityId: UUID,
    val idJsonPath: String,
)
