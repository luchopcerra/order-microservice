package orders

import kotlin.test.Test
import kotlin.test.assertEquals

class LifecycleTest {
    @Test
    fun lifecycle() {
        assertEquals(setOf("confirmed", "cancelled"), transitions.getValue("pending"))
        assertEquals(setOf("delivered"), transitions.getValue("shipped"))
    }
}
