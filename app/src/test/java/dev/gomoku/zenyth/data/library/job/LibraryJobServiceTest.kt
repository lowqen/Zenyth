package dev.gomoku.zenyth.data.library.job

import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import com.google.common.truth.Truth.assertThat
import dev.gomoku.zenyth.data.library.job.LibraryJobProtocol as P
import dev.gomoku.zenyth.domain.library.yxdb.DbStats
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.io.File

/**
 * The service's message round trip (L5-3 check «Robolectric 서비스 시험»):
 * hello · a scan by descriptor with its early stats · a cancel while paused ·
 * refusals. One process here — the kill and the separate heap are checked on
 * the phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LibraryJobServiceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class Got(val what: Int, val id: Int, val data: Bundle)

    private val got = ArrayList<Got>()
    private val replies = Messenger(Handler(Looper.getMainLooper()) { m -> got += Got(m.what, m.arg1, Bundle(m.data)); true })
    private lateinit var controller: ServiceController<LibraryJobService>
    private lateinit var service: Messenger

    @Before
    fun setUp() {
        controller = Robolectric.buildService(LibraryJobService::class.java).create()
        service = Messenger(controller.get().onBind(null))
    }

    @After
    fun tearDown() {
        controller.destroy()
    }

    private fun send(what: Int, id: Int = 0, arg2: Int = 0, data: Bundle? = null) {
        service.send(Message.obtain(null, what, id, arg2).also { m ->
            m.replyTo = replies
            if (data != null) m.data = data
        })
    }

    /** Lets the main looper run (the job thread posts to it) until [what] arrives. */
    private fun await(what: Int, timeoutMs: Long = 20_000): Got {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            got.firstOrNull { it.what == what }?.let { return it }
            Thread.sleep(5)
        }
        throw AssertionError("no message $what; got ${got.map { it.what }}")
    }

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("library/$name")).use { it.readBytes() }

    private fun db(name: String): File = tmp.newFile(name).also { it.writeBytes(resource(name)) }

    private fun read(f: File) = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)

    private fun start(id: Int, job: LibraryJob, ins: List<ParcelFileDescriptor>, yieldCode: Int = 0) = send(
        P.START, id, yieldCode,
        data = Bundle().apply {
            putString(P.KEY_JOB, JobJson.encode(job))
            P.putFds(this, P.KEY_IN, ins)
            P.putFds(this, P.KEY_OUT, emptyList())
            P.putFds(this, P.KEY_VERIFY, emptyList())
        },
    )

    @Test
    fun helloAnswersThePidAndTheHeapLimit() {
        send(P.HELLO)
        val h = await(P.HELLO_REPLY)
        assertThat(h.data.getInt(P.KEY_PID)).isEqualTo(android.os.Process.myPid())
        assertThat(h.data.getLong(P.KEY_HEAP_MAX)).isEqualTo(Runtime.getRuntime().maxMemory())
    }

    @Test
    fun aScanByDescriptorSendsEarlyStatsThenTheResult() {
        send(P.HELLO)
        await(P.HELLO_REPLY)
        val f = db("mixed.db")
        start(7, LibraryJob.Scan(f.length()), listOf(read(f)))

        val early = await(P.STATS)
        val result = await(P.RESULT)
        assertThat(early.id).isEqualTo(7)
        assertThat(result.id).isEqualTo(7)

        val j = Json { ignoreUnknownKeys = true }
        val want = j.decodeFromJsonElement(
            DbStats.serializer(),
            j.parseToJsonElement(resource("mixed.json").decodeToString()).jsonObject.getValue("stats"),
        )
        assertThat(JobJson.decodeStats(early.data.getString(P.KEY_JSON)!!)).isEqualTo(want.copy(openings = null, roots = null))
        val r = JobJson.decodeResult(result.data.getString(P.KEY_JSON)!!) as JobResult.Scanned
        assertThat(r.stats).isEqualTo(want)
        assertThat(got.filter { it.what == P.PROGRESS }.all { it.id == 7 }).isTrue()
        // Spill folder swept after the job.
        assertThat(File(P.spillRoot(controller.get()), "job-7").exists()).isFalse()
    }

    @Test
    fun aPausedBackgroundJobEndsCancelledOnCancel() {
        send(P.HELLO)
        await(P.HELLO_REPLY)
        val f = db("mixed.db")
        // Started paused (the engine is searching): it holds at its first chunk until cancelled.
        start(3, LibraryJob.Scan(f.length(), priority = JobPriority.BACKGROUND), listOf(read(f)), JobYield.ENGINE.code)
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(got.none { it.what == P.RESULT || it.what == P.STATS }).isTrue()
        send(P.CANCEL, 3)
        val c = await(P.CANCELLED)
        assertThat(c.id).isEqualTo(3)
        assertThat(got.any { it.what == P.CANCELLING && it.id == 3 }).isTrue()
        assertThat(got.none { it.what == P.RESULT }).isTrue()
    }

    @Test
    fun wrongDescriptorsAndBadJobsAreRefused() {
        send(P.HELLO)
        await(P.HELLO_REPLY)
        start(1, LibraryJob.Copy(10), listOf(read(db("mixed.db")))) // copy needs an output too
        val f = await(P.FAILED)
        assertThat(f.id).isEqualTo(1)
        assertThat(f.data.getString(P.KEY_MESSAGE)).contains("descriptors")

        got.clear()
        send(P.START, 2, data = Bundle().apply { putString(P.KEY_JOB, "{\"t\":\"nope\"}") })
        assertThat(await(P.FAILED).data.getString(P.KEY_MESSAGE)).startsWith("bad job")
    }
}
