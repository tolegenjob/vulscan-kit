package io.vulscan.kit.contracts

import java.time.Instant

enum class ViolationCode {
    MODULE_OPTIONS_MISMATCH,
    DEADLINE_IN_PAST,
    NON_POSITIVE_LIMIT,
    NO_ALLOWED_HOSTS,
    NO_ENDPOINTS,
    TOO_MANY_ENDPOINTS,
    ENDPOINT_OUT_OF_SCOPE,
    READ_ONLY_VIOLATION,
    IDOR_UNKNOWN_IDENTITY,
    IDOR_UNKNOWN_ENDPOINT,
    IDOR_TOO_FEW_IDENTITIES,
}

data class Violation(val code: ViolationCode, val message: String)

/**
 * Семантические проверки задания, которые JSON-схема выразить не может.
 * Модуль-специфичные правила (например, хотя бы одна проверка включена) проверяет сам модуль.
 */
object ScanJobPayloadValidator {
    const val MAX_ENDPOINTS = 200

    fun validate(
        payload: ScanJobPayload,
        now: Instant = Instant.now(),
    ): List<Violation> {
        val violations = mutableListOf<Violation>()
        fun add(code: ViolationCode, message: String) {
            violations += Violation(code, message)
        }

        val options = payload.moduleOptions
        val present = listOfNotNull(
            options.xss?.let { ModuleType.XSS },
            options.sqli?.let { ModuleType.SQLI },
            options.idor?.let { ModuleType.IDOR },
        )
        if (present != listOf(payload.moduleType)) {
            add(
                ViolationCode.MODULE_OPTIONS_MISMATCH,
                "moduleOptions must contain exactly one branch matching moduleType=${payload.moduleType}, found $present",
            )
        }

        if (!payload.deadlineAt.isAfter(now)) {
            add(ViolationCode.DEADLINE_IN_PAST, "deadlineAt ${payload.deadlineAt} is not in the future")
        }

        val limits = payload.limits
        val limitValues = mapOf(
            "maxRequests" to limits.maxRequests,
            "requestsPerSecond" to limits.requestsPerSecond,
            "maxConcurrency" to limits.maxConcurrency,
            "httpTimeoutMs" to limits.httpTimeoutMs,
            "maxResponseBytes" to limits.maxResponseBytes,
            "maxDurationSec" to limits.maxDurationSec,
        )
        limitValues.filterValues { it <= 0 }.keys.forEach { name ->
            add(ViolationCode.NON_POSITIVE_LIMIT, "limits.$name must be positive")
        }

        if (payload.target.allowedHosts.isEmpty()) {
            add(ViolationCode.NO_ALLOWED_HOSTS, "target.allowedHosts must not be empty")
        }

        if (payload.endpoints.isEmpty()) {
            add(ViolationCode.NO_ENDPOINTS, "endpoints must not be empty")
        }
        if (payload.endpoints.size > MAX_ENDPOINTS) {
            add(ViolationCode.TOO_MANY_ENDPOINTS, "endpoints count ${payload.endpoints.size} exceeds $MAX_ENDPOINTS")
        }

        payload.endpoints.forEach { endpoint ->
            val included = payload.scope.includePrefixes.any { endpoint.url.startsWith(it) }
            val excluded = payload.scope.excludePrefixes.any { endpoint.url.startsWith(it) }
            if (!included || excluded) {
                add(ViolationCode.ENDPOINT_OUT_OF_SCOPE, "endpoint ${endpoint.endpointId} url is outside the scope")
            }
        }

        options.idor?.let { idor -> validateIdor(payload, idor, ::add) }

        return violations
    }

    private fun validateIdor(
        payload: ScanJobPayload,
        idor: IdorOptions,
        add: (ViolationCode, String) -> Unit,
    ) {
        if (idor.identities.size < 2) {
            add(ViolationCode.IDOR_TOO_FEW_IDENTITIES, "idor requires at least 2 identities")
        }
        if (idor.mode == IdorMode.READ_ONLY) {
            payload.endpoints
                .filter { it.method != HttpMethod.GET && it.method != HttpMethod.HEAD }
                .forEach {
                    add(ViolationCode.READ_ONLY_VIOLATION, "endpoint ${it.endpointId} uses ${it.method} in READ_ONLY mode")
                }
        }
        val identityIds = idor.identities.map { it.identityId }.toSet()
        val endpointIds = payload.endpoints.map { it.endpointId }.toSet()
        idor.resources.forEach { resource ->
            if (resource.ownerIdentityId !in identityIds) {
                add(ViolationCode.IDOR_UNKNOWN_IDENTITY, "resource ${resource.resourceId} owner is not in identities")
            }
            resource.access.filter { it.identityId !in identityIds }.forEach {
                add(ViolationCode.IDOR_UNKNOWN_IDENTITY, "resource ${resource.resourceId} access rule refers to unknown identity")
            }
            if (resource.endpointId !in endpointIds) {
                add(ViolationCode.IDOR_UNKNOWN_ENDPOINT, "resource ${resource.resourceId} endpoint is not in endpoints")
            }
        }
    }
}
