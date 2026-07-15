import unittest

from app.main import TRANSITIONS


class LifecycleTest(unittest.TestCase):
    def test_lifecycle(self):
        self.assertEqual(TRANSITIONS["pending"], {"confirmed", "cancelled"})
        self.assertEqual(TRANSITIONS["confirmed"], {"shipped", "cancelled"})
        self.assertEqual(TRANSITIONS["shipped"], {"delivered"})
        self.assertEqual(TRANSITIONS["cancelled"], set())


if __name__ == "__main__":
    unittest.main()
