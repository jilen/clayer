package cats.layer

final class MakePartiallyApplied[R](val dummy: Boolean = true) extends AnyVal {
  inline def apply[E](inline layer: Layer[_, _]*): Layer[Any, R] =
    ${ LayerMacros.constructLayer[Any, R]('layer) }
}

final class MakeSomePartiallyApplied[R0, R](val dummy: Boolean = true)
    extends AnyVal {
  inline def apply[E](inline layer: Layer[_, _]*): Layer[R0, R] =
    ${ LayerMacros.constructLayer[R0, R]('layer) }
}
