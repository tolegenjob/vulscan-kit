package io.vulscan.kit.contracts

/** Тип модуля обнаружения. Совпадает с суффиксом Kafka-топика заданий. */
enum class ModuleType { XSS, SQLI, IDOR }

/** Тип сообщения в Kafka envelope. */
enum class MessageType { SCAN_JOB, FINDING_REPORTED, JOB_COMPLETED, JOB_FAILED }

enum class VulnType {
    XSS_REFLECTED,
    XSS_STORED,
    SQLI_ERROR_BASED,
    SQLI_BOOLEAN_BASED,
    SQLI_TIME_BASED,
    IDOR_HORIZONTAL,
    IDOR_VERTICAL,
}

/** SUSPECTED < LIKELY < CONFIRMED. */
enum class Verdict { SUSPECTED, LIKELY, CONFIRMED }

enum class Severity { INFO, LOW, MEDIUM, HIGH, CRITICAL }

enum class HttpMethod { GET, POST, PUT, PATCH, DELETE, HEAD }

enum class ParamLocation { QUERY, BODY_FORM, BODY_JSON, PATH, HEADER, COOKIE }

enum class ExpectedAccess { ALLOW, DENY, PUBLIC }

enum class Sensitivity { LOW, MEDIUM, HIGH }

enum class IdorMode { READ_ONLY }

enum class DbmsHint { POSTGRESQL, MYSQL, MSSQL, ORACLE, SQLITE }

enum class AuthType { FORM_LOGIN, JSON_LOGIN, BEARER_STATIC, COOKIE_STATIC, HEADER_STATIC }

/** Код ошибки задания в JOB_FAILED. */
enum class JobErrorCode {
    INVALID_JOB,
    SCOPE_VIOLATION,
    TARGET_UNREACHABLE,
    AUTH_FAILED,
    DEADLINE_EXCEEDED,
    INTERNAL_ERROR,
}
