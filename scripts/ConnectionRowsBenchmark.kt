import com.github.kr328.clash.core.model.Connection
import com.github.kr328.clash.design.connections.ActiveConnectionRows
import java.lang.management.ManagementFactory

fun main() {
    val results = mutableListOf<String>()
    for (size in listOf(100, 1000, 5000)) {
        for (scenario in listOf("unchanged", "traffic", "churn")) {
            val rows = ActiveConnectionRows()
            val times = mutableListOf<Double>()
            val thread = ManagementFactory.getThreadMXBean()
            val cpuBefore = thread.currentThreadCpuTime
            val memory = Runtime.getRuntime()
            System.gc()
            val before = memory.totalMemory() - memory.freeMemory()
            for (tick in 0 until 65) {
                val offset = if (scenario == "churn") tick * maxOf(1, size / 10) else 0
                val list = List(size) { i -> Connection(id = (i + offset).toString(),
                    download = if (scenario == "unchanged") 0L else tick * 100L,
                    start = "2026-09-14T00:00:00Z") }
                val start = System.nanoTime()
                rows.update(list, (tick + 1) * 1000L)
                check(rows.build(false).size == size)
                val ms = (System.nanoTime() - start) / 1e6
                if (tick >= 15) times.add(ms)
            }
            val cpuMs = (thread.currentThreadCpuTime - cpuBefore) / 1e6
            System.gc()
            val retained = memory.totalMemory() - memory.freeMemory() - before
            times.sort()
            results += """{"size":$size,"scenario":"$scenario","samples":50,"p50Ms":${times[24]},"p95Ms":${times[47]},"maxMs":${times.last()},"cpuMs":$cpuMs,"retainedHeapDeltaBytes":$retained,"budgetMs":50,"withinBudget":${times[47] <= 50}}"""
        }
    }
    println("""{"scope":"Synthetic desktop JVM production Android row builder and formatter; excludes Android rendering, Binder and JSON. CPU includes fixtures and GC; retained heap is not an allocation or leak verdict.","results":[${results.joinToString(",") }]}""")
}
