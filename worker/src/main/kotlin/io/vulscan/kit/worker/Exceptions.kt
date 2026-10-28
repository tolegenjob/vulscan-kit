package io.vulscan.kit.worker

/** Базовый класс для штатного прерывания задания рантаймом. */
sealed class JobAbortException(message: String) : RuntimeException(message)

class BudgetExhaustedException : JobAbortException("Request budget exhausted")

class DeadlineExceededException : JobAbortException("Job deadline exceeded")

class CancelledException : JobAbortException("Job cancelled")

class LeaseLostException : JobAbortException("Lease lost")

/** Core недоступен или ответил неожиданным кодом. */
class CoreUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
