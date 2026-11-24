package dev.gomoku.zenyth.bench

import dev.gomoku.zenyth.domain.library.yxdb.Lz4FrameDecoder
import dev.gomoku.zenyth.domain.library.yxdb.Lz4Sink
import dev.gomoku.zenyth.domain.library.yxdb.MergeOptions
import dev.gomoku.zenyth.domain.library.yxdb.MergeOut
import dev.gomoku.zenyth.domain.library.yxdb.MergePolicy
import dev.gomoku.zenyth.domain.library.yxdb.MergeSource
import dev.gomoku.zenyth.domain.library.yxdb.SplitBuckets
import dev.gomoku.zenyth.domain.library.yxdb.SplitCriterion
import dev.gomoku.zenyth.domain.library.yxdb.SplitOutput
import dev.gomoku.zenyth.domain.library.yxdb.SplitPlan
import dev.gomoku.zenyth.domain.library.yxdb.YxdbIndex
import dev.gomoku.zenyth.domain.library.yxdb.YxdbInput
import dev.gomoku.zenyth.domain.library.yxdb.YxdbKey
import dev.gomoku.zenyth.domain.library.yxdb.YxdbMerge
import dev.gomoku.zenyth.domain.library.yxdb.YxdbSplit
import java.io.File
import java.io.FileInputStream

/**
 * Library L5-0 — measures the Kotlin yixindb codec on a real file, on the
 * phone's ART (debug builds only; never in a release APK).
 *
 * ```
 * adb push sample.db /data/local/tmp/sample.db
 * adb shell 'CLASSPATH=$(pm path dev.gomoku.rapfidroid | cut -d: -f2) \
 *     app_process /system/bin dev.gomoku.zenyth.bench.LibraryBench /data/local/tmp/sample.db'
 * ```
 *
 * Args: `<file> [rounds=2] [--no-index] [--budget-mb=N] [--codec]`. Prints one
 * block per round (the first round includes JIT warm-up) plus peak RSS (VmHWM).
 * `--codec` (L5-1) also times merge (the file with itself: preview · write) and
 * split (solved: preview · write), writing to counting sinks only.
 * `--codec-out=DIR` writes those results to `DIR/l5merged.db` · `l5solved.db` ·
 * `l5rest.db` instead (last round only) — to read them back with the web codec.
 */
object LibraryBench {
    @JvmStatic
    fun main(args: Array<String>) {
        val path = args.firstOrNull() ?: run {
            println("usage: LibraryBench <file> [rounds] [--no-index] [--budget-mb=N]")
            return
        }
        val file = File(path)
        val rounds = args.drop(1).firstOrNull { it.all(Char::isDigit) }?.toInt() ?: 2
        val index = "--no-index" !in args
        val codec = "--codec" in args
        val codecOut = args.firstOrNull { it.startsWith("--codec-out=") }?.substringAfter('=')?.let(::File)
        val budget = args.firstOrNull { it.startsWith("--budget-mb=") }
            ?.substringAfter('=')?.toLong()?.shl(20) ?: Long.MAX_VALUE
        val rt = Runtime.getRuntime()
        println("file ${file.name} ${file.length()} B · maxMemory ${rt.maxMemory() shr 20} MB · cpus ${rt.availableProcessors()}")
        println("device ${prop("ro.product.model")} · soc ${prop("ro.soc.model")} · sdk ${prop("ro.build.version.sdk")}")

        for (round in 1..rounds) {
            // 1) LZ4 decode only — the share JNI could save.
            val t0 = System.nanoTime()
            var raw = 0L
            FileInputStream(file).use { input ->
                val dec = Lz4FrameDecoder(Lz4Sink { _, _, len -> raw += len })
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val r = input.read(buf)
                    if (r < 0) break
                    dec.push(buf, 0, r)
                }
                dec.end()
            }
            val decodeMs = (System.nanoTime() - t0) / 1_000_000

            // 2) stats only, 3) stats + index.
            val stats = YxdbIndex.scan({ FileInputStream(file) }, file.length(), index = false)
            val full = if (index) {
                YxdbIndex.scan({ FileInputStream(file) }, file.length(), index = true, maxIndexBytes = budget)
            } else null

            println("── round $round")
            println("decode   ${decodeMs} ms · raw ${raw shr 20} MB · ${"%.0f".format(raw / 1048576.0 / (decodeMs / 1000.0))} MB/s")
            println("stats    ${stats.totalMs} ms · records ${stats.stats.records} · rules ${stats.stats.rules} · sorted ${stats.sorted} · countOrdered ${stats.countOrdered} · dup ${stats.duplicates}")
            println("         labels ${stats.stats.labels} · commented ${stats.stats.commented}")
            if (full != null) {
                println("index    ${full.totalMs} ms ${full.ms} · indexBytes ${full.indexBytes?.shr(20)} MB (estimate ${YxdbIndex.bytesEstimate(full.count) shr 20} MB) · skipped ${full.indexSkipped}")
                full.stats.openings?.let { o ->
                    val top = o.withIndex().sortedByDescending { it.value }.take(3)
                    println("         openings top ${top.map { "${it.index}:${it.value}" }} · unclassified ${o[26]}")
                }
                full.stats.roots?.take(3)?.forEach { r ->
                    val k = YxdbKey.fromHex(r.key)
                    println("         root subtree ${r.subtree} · stones ${(k.size - 3) / 2}")
                }
            }
            if (codec) codecRound(file, codecOut?.takeIf { round == rounds })
            println("rss peak ${vmHwm()} · heap used ${(rt.totalMemory() - rt.freeMemory()) shr 20} MB")
            System.gc()
        }
    }

    /** Merge · split on the file, output counted and dropped. */
    private fun codecRound(file: File, outDir: File?) {
        fun sink(name: String, count: LongArray, i: Int): (ByteArray, Int, Int) -> Unit {
            if (outDir == null) return { _, _, l -> count[i] += l.toLong() }
            val s = java.io.BufferedOutputStream(java.io.FileOutputStream(File(outDir, name)), 1 shl 20)
            opened += s
            return { b, o, l -> s.write(b, o, l); count[i] += l.toLong() }
        }
        val input = YxdbInput.of(file)
        val sources = listOf(MergeSource(input, 0), MergeSource(input, 1))
        val opts = MergeOptions(MergePolicy.DEEPER, joinComments = true)
        var t = System.nanoTime()
        val pre = YxdbMerge.merge(sources, opts)
        val preMs = (System.nanoTime() - t) / 1_000_000
        val out = LongArray(1)
        t = System.nanoTime()
        YxdbMerge.merge(sources, opts, MergeOut(pre.records, sink("l5merged.db", out, 0)))
        closeAll()
        val writeMs = (System.nanoTime() - t) / 1_000_000
        println("merge    preview ${preMs} ms · write ${writeMs} ms (x2 inputs) · positions ${pre.positions} · overlaps ${pre.overlaps} · out ${out[0] shr 20} MB")

        t = System.nanoTime()
        val c = YxdbSplit.count(input, null, withOpenings = false)
        val countMs = (System.nanoTime() - t) / 1_000_000
        val plan = SplitPlan(SplitCriterion.SOLVED, listOf(SplitOutput(listOf(SplitBuckets.SOLVED), c.solved)), rest = c.total - c.solved)
        val sizes = LongArray(2)
        t = System.nanoTime()
        YxdbSplit.run(input, plan, listOf(sink("l5solved.db", sizes, 0), sink("l5rest.db", sizes, 1)), c.meta)
        closeAll()
        val runMs = (System.nanoTime() - t) / 1_000_000
        println("split    preview ${countMs} ms · write ${runMs} ms · solved ${c.solved} / ${c.total} · out ${sizes[0] shr 20} + ${sizes[1] shr 20} MB")
    }

    private val opened = ArrayList<java.io.Closeable>()

    private fun closeAll() {
        opened.forEach { it.close() }
        opened.clear()
    }

    private fun vmHwm(): String =
        runCatching { File("/proc/self/status").readLines().first { it.startsWith("VmHWM") }.substringAfter(':').trim() }
            .getOrDefault("?")

    private fun prop(key: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as String
    }.getOrDefault("?")
}
