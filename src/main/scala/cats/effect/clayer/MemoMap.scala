package cats.effect.clayer

/**
 * A `MemoMap` memoizes dependencies.
 */
private[clayer] abstract class MemoMap[F[_]] { self =>

  /**
   * Checks the memo map to see if a dependency exists. If it is, immediately
   * returns it. Otherwise, obtains the dependency, stores it in the memo map,
   * and adds a finalizer to the outer `Managed`.
   */
  def getOrElseMemoize[A, B](layer: CLayer[F, A,  B]): Managed[F, A,  B]
}

private[clayer] object MemoMap {

  /**
   * Constructs an empty memo map.
   */
  def make[F[_]]: F[MemoMap[F]] = ???
}
