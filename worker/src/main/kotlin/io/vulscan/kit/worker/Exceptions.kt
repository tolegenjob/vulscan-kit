package io.vulscan.kit.worker

/** Базовый класс для штатного прерывания задания рантаймом. */
sealed class JobAbortException(message: String) : RuntimeException(message)

class BudgetExhaustedException : JobAbortException("Request budget exhausted")

class DeadlineExceededException : JobAbortException("Job deadline exceeded")

class CancelledException : JobAbortException("Job cancelled")

class LeaseLostException : JobAbortException("Lease lost")

/** Core недоступен или ответил неожиданным кодом. */
class CoreUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Задание некорректно для данного модуля (например, ни одна проверка не включена). Приводит к JOB_FAILED(INVALID_JOB) без повтора. */
class InvalidJobException(message: String) : RuntimeException(message)

/** Аутентификация задания провалилась так, что продолжать нельзя (например, две тестовые идентичности совпали). Приводит к JOB_FAILED(AUTH_FAILED) без повтора. */
class AuthFailedException(message: String) : RuntimeException(message)
