package com.example.data.update

/** Version compare for GitHub release tags. Pure Kotlin (unit-tested). */
object AppVersion {

    /** "v1.2.0" / "1.2" / "1.2.0-beta" -> [1, 2, 0]. */
    fun parse(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore(' ')
            .split('.').map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    /** true when [remote] is a higher version than [local] (1.10 > 1.9, 1.1 == 1.1.0). */
    fun isNewer(remote: String, local: String): Boolean {
        val a = parse(remote)
        val b = parse(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
