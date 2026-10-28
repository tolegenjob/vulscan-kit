package io.vulscan.kit.worker

import io.vulscan.kit.contracts.KafkaTopics
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.WakeupException
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.Properties
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

fun interface DeadLetterSink {
    fun send(key: String?, value: String?, reason: String)
}

class KafkaDeadLetterSink(private val producer: KafkaProducer<String, String>) : DeadLetterSink {
    override fun send(key: String?, value: String?, reason: String) {
        val record = ProducerRecord(KafkaTopics.DLQ, key, value)
        record.headers().add("x-dlq-reason", reason.toByteArray(Charsets.UTF_8))
        producer.send(record).get(10, TimeUnit.SECONDS)
    }
}

/**
 * Цикл потребления заданий. Одно задание выполняется в пуле из `maxConcurrentJobs` потоков;
 * пока свободных слотов нет, потребление приостанавливается (сообщение не подтверждается).
 * Смещение подтверждается после claim (или после решения не запускать задание).
 */
class WorkerRuntime(
    private val engine: DetectionEngine,
    private val properties: WorkerProperties,
    private val consumer: Consumer<String, String>,
    private val processor: JobProcessor,
    private val executor: JobExecutor,
    private val deadLetters: DeadLetterSink,
    private val pollTimeout: Duration = Duration.ofMillis(500),
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(WorkerRuntime::class.java)
    private val slots = Semaphore(properties.maxConcurrentJobs)
    private val pool: ExecutorService = Executors.newFixedThreadPool(properties.maxConcurrentJobs) { r ->
        Thread(r, "worker-job").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)
    private var loopThread: Thread? = null
    private var paused = false

    fun start() {
        check(running.compareAndSet(false, true)) { "already started" }
        consumer.subscribe(listOf(KafkaTopics.jobsFor(engine.moduleType)))
        loopThread = Thread(::loop, "worker-consumer").also { it.start() }
    }

    private fun loop() {
        try {
            while (running.get()) {
                if (paused && slots.availablePermits() > 0) {
                    consumer.resume(consumer.paused())
                    paused = false
                }
                val records = consumer.poll(pollTimeout)
                val rewind = mutableMapOf<TopicPartition, Long>()
                var blocked = false
                for (record in records) {
                    val tp = TopicPartition(record.topic(), record.partition())
                    if (blocked) {
                        rewind.merge(tp, record.offset()) { a, b -> minOf(a, b) }
                        continue
                    }
                    if (!handle(record.key(), record.value())) {
                        blocked = true
                        rewind.merge(tp, record.offset()) { a, b -> minOf(a, b) }
                        continue
                    }
                    consumer.commitSync(mapOf(tp to OffsetAndMetadata(record.offset() + 1)))
                }
                if (blocked) {
                    consumer.pause(consumer.assignment())
                    paused = true
                    rewind.forEach { (tp, offset) -> consumer.seek(tp, offset) }
                }
            }
        } catch (_: WakeupException) {
            // штатная остановка
        } catch (e: Exception) {
            log.error("Consumer loop terminated", e)
        } finally {
            consumer.close()
        }
    }

    /** @return false, если нет свободного слота и сообщение нужно обработать позже. */
    private fun handle(key: String?, value: String?): Boolean {
        when (val prepared = processor.prepare(value)) {
            is Prepared.DeadLetter -> {
                deadLetters.send(key, value, prepared.reason)
                return true
            }
            is Prepared.Skip -> {
                log.info("Message skipped: {}", prepared.reason)
                return true
            }
            is Prepared.Ready -> {
                if (!slots.tryAcquire()) return false
                val claimed = try {
                    processor.claim(prepared.job)
                } catch (e: CoreUnavailableException) {
                    slots.release()
                    deadLetters.send(key, value, "CORE_UNAVAILABLE")
                    return true
                }
                when (claimed) {
                    is Claimed.Rejected -> slots.release()
                    is Claimed.Granted -> pool.submit {
                        try {
                            executor.execute(prepared.job, prepared.correlationId, claimed.response)
                        } finally {
                            slots.release()
                        }
                    }
                }
                return true
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        consumer.wakeup()
        loopThread?.join(10_000)
        pool.shutdownNow()
    }

    override fun close() = stop()

    companion object {
        fun create(engine: DetectionEngine, properties: WorkerProperties): WorkerRuntime {
            val core = HttpCoreControlClient(properties.coreBaseUrl, properties.serviceToken, properties.workerId)
            val producer = KafkaProducer<String, String>(
                Properties().apply {
                    put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.bootstrapServers)
                    put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
                    put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
                    put(ProducerConfig.ACKS_CONFIG, "all")
                    put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true")
                },
            )
            val consumer = KafkaConsumer<String, String>(
                Properties().apply {
                    put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.bootstrapServers)
                    put(ConsumerConfig.GROUP_ID_CONFIG, properties.consumerGroup)
                    put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                    put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                    put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
                    put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                    put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "10")
                },
            )
            val publisher = KafkaResultPublisher(producer)
            return WorkerRuntime(
                engine, properties, consumer,
                JobProcessor(engine.moduleType, properties.hardLimits, core, properties.workerId),
                JobExecutor(engine, core, publisher, properties),
                KafkaDeadLetterSink(producer),
            )
        }
    }
}
