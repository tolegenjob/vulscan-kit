package io.vulscan.kit.worker

import com.sun.net.httpserver.HttpServer
import io.vulscan.kit.contracts.JobErrorCode
import io.vulscan.kit.contracts.JobFailedPayload
import io.vulscan.kit.contracts.Json
import io.vulscan.kit.contracts.KafkaTopics
import io.vulscan.kit.contracts.MessageType
import io.vulscan.kit.contracts.ModuleType
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.kafka.KafkaContainer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Интеграционный тест с настоящим Kafka. Пропускается, если Docker недоступен. */
class WorkerRuntimeKafkaIT {
    private var kafka: KafkaContainer? = null
    private var core: HttpServer? = null
    private var runtime: WorkerRuntime? = null
    private val claimCount = AtomicInteger()
    private val claimStatuses = CopyOnWriteArrayList<Int>()
    private lateinit var producer: KafkaProducer<String, String>

    @BeforeEach
    fun setUp() {
        val docker = runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
        // В CI тест обязан выполняться, локально без Docker он пропускается
        if (System.getenv("CI") == "true") check(docker) { "Docker is required in CI" } else assumeTrue(docker, "Docker is not available")
        kafka = KafkaContainer("apache/kafka:3.9.1").also { it.start() }
        core = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0).also { server ->
            server.createContext("/internal/v1/jobs") { ex ->
                ex.requestBody.readBytes()
                val (status, body) = if (ex.requestURI.path.endsWith("/claim")) {
                    val index = claimCount.getAndIncrement()
                    val status = claimStatuses.getOrElse(index) { 200 }
                    status to if (status == 200) """{"leaseExpiresAt":"2030-01-01T00:00:00Z","heartbeatIntervalSec":1}""" else ""
                } else {
                    200 to """{"cancel":false,"leaseExpiresAt":"2030-01-01T00:00:00Z"}"""
                }
                val bytes = body.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
                ex.close()
            }
            server.start()
        }
        producer = KafkaProducer(
            Properties().apply {
                put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka!!.bootstrapServers)
                put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
                put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            },
        )
    }

    @AfterEach
    fun tearDown() {
        runCatching { runtime?.stop() }
        runCatching { producer.close(Duration.ofSeconds(2)) }
        runCatching { core?.stop(0) }
        runCatching { kafka?.stop() }
    }

    private fun startRuntime(engine: DetectionEngine, maxJobs: Int = 1) {
        val props = WorkerProperties(
            coreBaseUrl = "http://127.0.0.1:${core!!.address.port}",
            serviceToken = "tok",
            bootstrapServers = kafka!!.bootstrapServers,
            consumerGroup = "it-" + UUID.randomUUID(),
            maxConcurrentJobs = maxJobs,
        )
        runtime = WorkerRuntime.create(engine, props).also { it.start() }
    }

    private fun send(topic: String, key: String, value: String) {
        producer.send(ProducerRecord(topic, key, value)).get(10, TimeUnit.SECONDS)
    }

    private fun sendJob(job: io.vulscan.kit.contracts.ScanJobPayload = Fixtures.job()) =
        send(KafkaTopics.jobsFor(ModuleType.XSS), job.jobId.toString(), Fixtures.jobMessage(job))

    private fun readTopic(topic: String, timeoutSec: Long = 30, until: (List<String>) -> Boolean): List<String> {
        val consumer = KafkaConsumer<String, String>(
            Properties().apply {
                put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka!!.bootstrapServers)
                put(ConsumerConfig.GROUP_ID_CONFIG, "reader-" + UUID.randomUUID())
                put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            },
        )
        val values = mutableListOf<String>()
        consumer.use {
            it.subscribe(listOf(topic))
            val end = System.currentTimeMillis() + timeoutSec * 1000
            while (System.currentTimeMillis() < end && !until(values)) {
                it.poll(Duration.ofMillis(300)).forEach { r -> values += r.value() }
            }
        }
        return values
    }

    private fun events(type: MessageType, values: List<String>) = values.map(Json::readEnvelope).filter { it.type == type }

    private fun awaitCondition(timeoutSec: Long = 30, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutSec * 1000
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    @Test
    fun `valid job is claimed run once and completed`() {
        val engine = LambdaEngine { it.stats.endpointTested() }
        startRuntime(engine)
        sendJob()
        val values = readTopic(KafkaTopics.JOB_EVENTS) { events(MessageType.JOB_COMPLETED, it).isNotEmpty() }
        assertThat(events(MessageType.JOB_COMPLETED, values)).hasSize(1)
        assertThat(engine.runs.get()).isEqualTo(1)
    }

    @Test
    fun `duplicate delivery with claim conflict runs the engine once`() {
        claimStatuses += listOf(200, 409)
        val engine = LambdaEngine { }
        startRuntime(engine)
        val job = Fixtures.job()
        sendJob(job)
        sendJob(job)
        assertThat(awaitCondition { claimCount.get() >= 2 }).isTrue()
        Thread.sleep(1500)
        assertThat(engine.runs.get()).isEqualTo(1)
    }

    @Test
    fun `message for another module goes to the dead letter queue`() {
        val engine = LambdaEngine { }
        startRuntime(engine)
        val wrong = Fixtures.job().copy(moduleType = ModuleType.SQLI)
        send(KafkaTopics.jobsFor(ModuleType.XSS), wrong.jobId.toString(), Fixtures.jobMessage(wrong))
        val dlq = readTopic(KafkaTopics.DLQ) { it.isNotEmpty() }
        assertThat(dlq).hasSize(1)
        assertThat(engine.runs.get()).isZero()
        assertThat(claimCount.get()).isZero()
    }

    @Test
    fun `engine failure is published as a retryable internal error`() {
        val engine = LambdaEngine { throw IllegalStateException("boom") }
        startRuntime(engine)
        sendJob()
        val values = readTopic(KafkaTopics.JOB_EVENTS) { events(MessageType.JOB_FAILED, it).isNotEmpty() }
        val failed = Json.payloadAs(events(MessageType.JOB_FAILED, values).single(), JobFailedPayload::class.java)
        assertThat(failed.errorCode).isEqualTo(JobErrorCode.INTERNAL_ERROR)
        assertThat(failed.retryable).isTrue()
    }

    @Test
    fun `second job is claimed only after the first one finishes`() {
        val release = CountDownLatch(1)
        val engine = LambdaEngine { release.await(30, TimeUnit.SECONDS) }
        startRuntime(engine, maxJobs = 1)
        sendJob()
        sendJob()
        assertThat(awaitCondition { claimCount.get() >= 1 }).isTrue()
        Thread.sleep(3000)
        assertThat(claimCount.get()).isEqualTo(1)
        release.countDown()
        assertThat(awaitCondition { claimCount.get() >= 2 }).isTrue()
        assertThat(awaitCondition { engine.runs.get() >= 2 }).isTrue()
    }
}
