package cats.effect.clayer

import cats._
import cats.effect._

trait ManagedSyntax {
  implicit class ManagedOps[F[_], R, A](self: Managed[F, R, A]) {
    def provide[R](r: R)(implicit ev: NeedsEnv[R]): Managed[F, Any, A] = {
      ???
    }

    def provideSome[R0](f: R0 => R)(implicit ev: NeedsEnv[R]): Managed[R0, A] = {
      val newR = self.contramap[(R0, Managed.ReleaseMap)](tp => f(tp._1) -> tp._2)
      Managed(newR)
    }

    def useForever: R => F[Nothing]

    def use[R1 <: R, A](f: R => (R1 => F[A])): R1 => F[A] = {
      ???
    }

    def memoize: Managed[F, Any, Managed[F, R, A]] = {
      ???
    }

    def zipWith[R1 <: R, A1, A2](that: Managed[F, R1, A1])(f: (A, A1) => A2): Managed[F, R1, A2] = {
      (self, that).mapN(f)
    }

    def zipWithPar[R1 <: R, A1, A2](that: Managed[F, R1, A1])(f: (A, A1) => A2): Managed[F, R1, A2] = {
      (self, that).parMapN(f)
    }


  }
}

object Managed {

  def make[F[_], R, A](acquire: R => F[A])(release: A => R => F[Any]): Managed[F, R, A] = {
    ???
  }

  def fail[F[_]: MonadError[*[_], Throwable]](e: Throwable): Managed[F, Any, Nothing] = {
    MonadError[F, Throwable].raiseError(e)
  }

  def environment[F[_], R]: Managed[F, R, R] =
    fromFunction(identity[R])

  def succeed[F[_], A](r: => A): Managed[F, Any, A] = {
      fromFunction(_ => r)
    }

  def fromFunction[F[_]: Applicative, R, A](f: R => A) = {
    evalFunction(f.andThen(Applicative[F].pure))
  }

  def evalFunction[F[_], R, A](fa: R => F[A]): Managed[F, R, A] = {
    Managed(fa.andThen(Resource.eval[F]))
  }

  def eval[F[_], A](fa: F[A]): Managed[F, Any, A] = {
    evalFunction(_ => fa)
  }


  object ReleaseMap {
    def make[F[_]]: F[ReleaseMap[F]] = ???
  }


  abstract class ReleaseMap[F[_]] {

    /**
     * An opaque identifier for a finalizer stored in the map.
     */
    type Key

    /**
     * Adds a finalizer to the finalizers associated with this scope. If the
     * finalizers associated with this scope have already been run this
     * finalizer will be run immediately.
     *
     * The finalizer returned from this method will remove the original finalizer
     * from the map and run it.
     */
    def add(finalizer: Finalizer[F]): F[Finalizer[F]]

    /**
     * Adds a finalizer to the finalizers associated with this scope. If the
     * scope is still open, a [[Key]] will be returned. This is an opaque identifier
     * that can be used to activate this finalizer and remove it from the map.
     * from the map. If the scope has been closed, the finalizer will be executed
     * immediately (with the [[Exit]] value with which the scope has ended) and
     * no Key will be returned.
     */
    def addIfOpen(finalizer: Finalizer[F]): F[Option[Key]]

    /**
     * Retrieves the finalizer associated with this key.
     */
    def get(key: Key): F[F[Finalizer[F]]]

    /**
     * Runs the specified finalizer and removes it from the finalizers
     * associated with this scope.
     */
    def release(key: Key, exit: Resource.ExitCase): F[Unit]

    /**
     * Runs the finalizers associated with this scope using the specified
     * execution strategy. After this action finishes, any finalizers added
     * to this scope will be run immediately.
     */
    def releaseAll(exit: Resource.ExitCase): F[Unit]

    /**
     * Removes the finalizer associated with this key and returns it.
     */
    def remove(key: Key): F[Option[Finalizer[F]]]

    /**
     * Replaces the finalizer associated with this key and returns it.
     * If the finalizers associated with this scope have already been run this
     * finalizer will be run immediately.
     */
    def replace(key: Key, finalizer: Finalizer[F]): F[Option[Finalizer[F]]]
  }
}
