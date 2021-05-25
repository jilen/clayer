package cats.effect

import cats._
import cats.data._

package object clayer  extends Tags {
  type Raise[F[_]] = cats.MonadError[F, Throwable]
  object Raise {
    def apply[F[_]](implicit F: Raise[F]): Raise[F] = F
  }
  type Finalizer[F[_]] = Resource.ExitCase => F[Unit]
}
