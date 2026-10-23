package com.drkkr.strategos

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DomainPurityTest {

    private fun domainDir(): File {
        val candidates = listOf(
            "src/main/kotlin/com/drkkr/strategos/domain",
            "app/src/main/kotlin/com/drkkr/strategos/domain"
        )
        return candidates.map(::File).first { it.isDirectory }
    }

    @Test
    fun TC_PURE_01_domainHasNoAndroidImports() {
        val offenders = domainDir().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { f ->
                f.readLines().withIndex()
                    .filter { (_, line) -> line.startsWith("import android") || line.startsWith("import androidx") }
                    .map { (i, line) -> "${f.name}:${i + 1}: $line" }
            }
            .toList()
        assertTrue("domain/ must not import android.* or androidx.*:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
