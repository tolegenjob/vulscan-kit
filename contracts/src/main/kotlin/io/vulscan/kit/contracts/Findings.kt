package io.vulscan.kit.contracts

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.util.UUID

/** Payload сообщения FINDING_REPORTED (модуль -> Core). */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class FindingReportedPayload(
    val jobId: UUID,
    val attempt: Int,
    val finding: FindingPayload,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class FindingPayload(
    val moduleType: ModuleType,
    val vulnType: VulnType,
    val cwe: String,
    val endpointId: UUID? = null,
    val method: HttpMethod,
    val url: String,
    val parameter: ParameterId? = null,
    val verdict: Verdict,
    val severity: Severity,
    /** 1..99 */
    val confidence: Int,
    /** SHA-256 hex (64 символа), см. [Fingerprint]. */
    val fingerprint: String,
    val title: String,
    val summary: String,
    val recommendation: String,
    val details: FindingDetails,
    val evidence: Evidence,
)

/**
 * Детали зависят от vulnType. Дискриминатор — поле `type`, равное vulnType.
 * Поле `type` объявлено в самих классах, поэтому Jackson его не дублирует при записи.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "type",
    visible = true,
)
@JsonSubTypes(
    JsonSubTypes.Type(value = XssDetails::class, names = ["XSS_REFLECTED", "XSS_STORED"]),
    JsonSubTypes.Type(value = SqliDetails::class, names = ["SQLI_ERROR_BASED", "SQLI_BOOLEAN_BASED", "SQLI_TIME_BASED"]),
    JsonSubTypes.Type(value = IdorDetails::class, names = ["IDOR_HORIZONTAL", "IDOR_VERTICAL"]),
)
sealed interface FindingDetails {
    val type: VulnType
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class XssDetails(
    override val type: VulnType,
    /** Например HTML_TEXT, ATTR_DOUBLE_QUOTED, SCRIPT_STRING_DQ. */
    val context: String,
    val survivingChars: List<String> = emptyList(),
    /** DOM_STRUCTURE, JS_STRING_BREAK, BROWSER_EXECUTION или NONE. */
    val injectionProof: String,
    val mitigations: List<String> = emptyList(),
) : FindingDetails

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SqliDetails(
    override val type: VulnType,
    val dbms: String? = null,
    val baselineSimilarity: Double? = null,
    val similarityThreshold: Double? = null,
    val matchedSignature: String? = null,
    val methodsConfirmed: List<String> = emptyList(),
) : FindingDetails

@JsonInclude(JsonInclude.Include.NON_NULL)
data class IdorDetails(
    override val type: VulnType,
    val ownerLabel: String,
    val attackerLabel: String,
    val markersMatched: List<String> = emptyList(),
    val markersTotal: Int = 0,
    val repeatConfirmed: Boolean = false,
    val anonymousStatus: Int? = null,
    /** Например OWNER_BY_LISTING, если владелец определён по списку. */
    val assumption: String? = null,
) : FindingDetails

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Evidence(
    val request: HttpRequestEvidence,
    val response: HttpResponseEvidence? = null,
    val observations: List<Observation> = emptyList(),
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class HttpRequestEvidence(
    val method: HttpMethod,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val bodyExcerpt: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class HttpResponseEvidence(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    /** Не более 2048 символов. */
    val bodyExcerpt: String? = null,
    val highlights: List<Highlight> = emptyList(),
)

/**
 * Диапазон внутри bodyExcerpt. Индексы считаются в UTF-16 code units
 * (как в String.length и индексах строк JavaScript): 0 <= start < end <= bodyExcerpt.length.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Highlight(
    val start: Int,
    val end: Int,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Observation(
    val key: String,
    val value: String,
)
