package orders

class LifecycleSuite extends munit.FunSuite:
  test("lifecycle") {
    assertEquals(Main.transitions("pending"), Set("confirmed", "cancelled"))
    assertEquals(Main.transitions("shipped"), Set("delivered"))
  }
