package com.drkkr.strategos.domain.framework

import java.util.Locale

/**
 * All numeric formatting for framework module output.
 * Never pass Kotlin string-interpolated percent signs through String.format.
 * Always format numbers first, then interpolate into plain Kotlin strings.
 */
object SafeFormat {

    fun f2(value: Double): String =
        if (value.isNaN() || value.isInfinite()) "—"
        else String.format(Locale.US, "%.2f", value)

    fun f3(value: Double): String =
        if (value.isNaN() || value.isInfinite()) "—"
        else String.format(Locale.US, "%.3f", value)

    fun f1(value: Double): String =
        if (value.isNaN() || value.isInfinite()) "—"
        else String.format(Locale.US, "%.1f", value)

    fun pct0(rate: Double): String =
        if (rate.isNaN() || rate.isInfinite()) "—%"
        else "${(rate * 100).toInt()}%"

    fun pct1(rate: Double): String =
        if (rate.isNaN() || rate.isInfinite()) "—%"
        else "${String.format(Locale.US, "%.1f", rate * 100)}%"

    fun hex2(b: Byte): String = String.format(Locale.US, "%02x", b)
}
