package cats.effect

package object clayer  extends Tags with ManagedInstances {
  type Raise[F[_]] = cats.MonadError[F, Throwable]
  object Raise {
    def apply[F[_]](implicit F: Raise[F]): Raise[F] = F
  }
}
