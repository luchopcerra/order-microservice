package orders

import orders.domain.OrderStatus

class LifecycleSuite extends munit.FunSuite:
  test("lifecycle") {
    assertEquals(OrderStatus.transitions("pending"), Set("confirmed", "cancelled"))
    assertEquals(OrderStatus.transitions("shipped"), Set("delivered"))
  }
