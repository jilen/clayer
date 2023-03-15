package cats.layer.macros

sealed trait GraphError[+Key, +A]

object GraphError {
  def missingTransitiveDependency[Key, A](
      node: Node[Key, A],
      dependency: Key
  ): GraphError[Key, A] =
    MissingTransitiveDependencies(node, Vector(dependency))

  case class MissingTransitiveDependencies[+Key, +A](
      node: Node[Key, A],
      dependency: Vector[Key]
  ) extends GraphError[Key, A]

  case class MissingTopLevelDependency[+Key](requirement: Key)
      extends GraphError[Key, Nothing]

  case class CircularDependency[+Key, +A](
      node: Node[Key, A],
      dependency: Node[Key, A],
      depth: Int = 0
  ) extends GraphError[Key, A]
}
