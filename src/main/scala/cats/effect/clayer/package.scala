package cats.effect

package object clayer  extends Tags {
  type Raise[F[_]] = cats.MonadError[F, Throwable]
}
