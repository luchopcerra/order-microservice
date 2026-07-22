package orders.domain

object OrderStatus:
  val transitions: Map[String, Set[String]] = Map(
    "pending"   -> Set("confirmed", "cancelled"),
    "confirmed" -> Set("shipped", "cancelled"),
    "shipped"   -> Set("delivered"),
    "delivered" -> Set.empty,
    "cancelled" -> Set.empty
  )

  def isKnown(status: String): Boolean = transitions.contains(status)

  def canTransition(from: String, to: String): Boolean =
    transitions.getOrElse(from, Set.empty).contains(to)
