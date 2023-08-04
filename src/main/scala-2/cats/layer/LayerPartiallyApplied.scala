package cats.layer

import scala.language.experimental.macros

final class MakePartiallyApplied[R](val dummy: Boolean = true) extends AnyVal {
  def apply(
      layer: Layer[_, _]*
  )(implicit
      dummyKRemainder: DummyK[Any],
      dummyK: DummyK[R]
  ): Layer[Any, R] =
    macro LayerMakeMacros.makeImpl[Any, R]
}

final class MakeSomePartiallyApplied[R0, R](
    val dummy: Boolean = true
) extends AnyVal {
  def apply(
      layer: Layer[_, _]*
  )(implicit dummyKRemainder: DummyK[R0], dummyK: DummyK[R]): Layer[R0, R] =
    macro LayerMakeMacros.makeSomeImpl[R0, R]
}
