package orders;

import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class LifecycleTest {
  @Test
  void exposesErrorEnvelope() {
    assertTrue(App.err("bad", "CODE").containsKey("error"));
  }
}
