package cats.effect.clayer

import cats._
import cats.effect._

object Managed {
  def liftF[F[_], A](f: F[A]): Managed[F, Any, A] = { _ =>
    Resource.liftF(fa)
  }
}

class Managed[F[_], -RIn, +ROut](
  underlying: RIn => F[Resource[F, ROut]]
)

trait ManagedInstances {
  implicit def ManagedMonadError[F[_]: MonadError[*[_], Throwable], R]: MonadError[Managed[F, R, *], Throwable] = new MonadError[Managed[F, R, *], Throwable] {

  }
}
