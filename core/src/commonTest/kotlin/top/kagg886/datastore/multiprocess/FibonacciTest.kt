package top.kagg886.datastore.multiprocess

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FibonacciTest {
    @Test
    fun scaffoldRunsOnEveryTarget() {
        assertEquals(emptyList(), getFibonacciNumbers(0))
        assertEquals(listOf(0, 1, 1, 2, 3, 5), getFibonacciNumbers(6))
        assertFailsWith<IllegalArgumentException> { getFibonacciNumbers(-1) }
    }
}
