package cats.effect.clayer

import cats._
import cats.data._
import cats.evidence._
import cats.syntax.all._
import cats.effect._

/**
 * A managed resource produced from an environment R
 */
final case class Managed[F[_], -R, +A](run: R => Resource[F, A]) { self =>
  def provide[R](r: R)(implicit ev: NeedsEnv[R]): Managed[F, Any, A] = {
    ???
  }

  def provideSome[R0](f: R0 => R)(implicit ev: NeedsEnv[R]): Managed[F, R0, A] = {
    val nf = Contravariant[Function1[*, Resource[F, A]]].contramap[R, R0](run)(f)
    Managed(nf)
  }

  def useForever: R => F[Nothing] = {
    ???
  }

  def use[R1 <: R, A](f: R1 => (R1 => F[A])): R1 => F[A] = {
    ???
  }

  def memoize: Managed[F, Any, Managed[F, R, A]] = {
    ???
  }

  def zipWith[R1 <: R, A1, A2](that: Managed[F, R1, A1])(f: (A, A1) => A2)(implicit F: MonadError[F, Throwable]): Managed[F, R1, A2] = {
    (this, that).mapN(f)
  }

  def zipWithPar[R1 <: R, A1, A2](that: Managed[F, R1, A1])(f: (A, A1) => A2)(implicit F: MonadError[F, Throwable], P: Parallel[F]): Managed[F, R1, A2] = {
    (self, that).parMapN(f)
  }

  def asService[A1 >: A: Tag](implicit F: MonadError[F, Throwable]): Managed[F, R, Has[A1]] = {
    self.map(Has(_))
  }

  private[clayer] def ap[C, R1 <: R, B](f: Managed[F, R1, A => B])(implicit F: MonadError[F, Throwable]): Managed[F, R1, B] = {
    Managed(a => Apply[Resource[F, *]].ap(f.run(a))(run(a)))
  }

  def ap[C, D, R1 <: R](f: Managed[F, R1, C])(implicit F: MonadError[F, Throwable], ev: A As (C => D)): Managed[F, R1, D] = {
    val RF = MonadError[Resource[F, *], Throwable]
    Managed { a =>
      val fb: Resource[F, C => D] = RF.map(run(a))(ev.coerce)
      val fc: Resource[F, C] = f.run(a)
      RF.ap(fb)(fc)
    }
  }

  /**
   *  Help type inference
   */
  def flatMap[R1 <: R, B](f: A => Managed[F, R1,  B]): Managed[F, R1, B] = {
    val nf: R1 => Resource[F, B] = { r1 =>
      this.run(r1).flatMap { a =>
        f(a).run(r1)
      }
    }
    Managed(nf)
  }
}


trait ManagedInstances {
  private[clayer] trait ManagedMonadError[F[_], A] extends MonadError[Managed[F, A, *], Throwable] with StackSafeMonad[Managed[F, A, *]] {

    type M[B] = Managed[F, A, B]

    implicit def F: MonadError[F, Throwable]
    implicit def MF = MonadError[Resource[F, *], Throwable]

    def pure[B](b: B): Managed[F, A, B] = Managed.succeedNow(b)

    def raiseError[B](e: Throwable): M[B] = Managed(_ => MF.raiseError(e))

    def handleErrorWith[B](kb: M[B])(f: Throwable => M[B]): M[B] =
      Managed { (a: A) =>
        MF.handleErrorWith(kb.run(a))((e: Throwable) => f(e).run(a))
      }

    def flatMap[A, B](fa: M[A])(f: A => M[B]) = fa.flatMap(f)
  }

  implicit def managedMonadErrorInstance[F[_]: MonadError[*[_], Throwable], A]: MonadError[Managed[F, A, *], Throwable] = new ManagedMonadError[F, A] {
    def F = MonadError[F, Throwable]
  }

  implicit def managedContravariant[F[_], B]: Contravariant[Managed[F, *, B]] = new Contravariant[Managed[F, *, B]]{
    def contramap[A, A1](fa: Managed[F, A, B])(f: A1 => A): Managed[F, A1, B] = {
      val newRun = Contravariant[(* => Resource[F, B])].contramap(fa.run)(f)
      Managed(newRun)
    }
  }

  implicit def managedParallel[M[_], A](implicit P: Parallel[M], M: MonadError[M, Throwable]): Parallel.Aux[Managed[M, A, *], Managed[P.F, A, *]] = new Parallel[Managed[M, A, *]]{
    type F[x] = Kleisli[P.F, A, x]
    implicit val monadM: Monad[M] = P.monad
    def applicative: Applicative[Managed[P.F, A, *]] = catsDataApplicativeForManaged(P.applicative)
    def monad: Monad[Managed[M, A, *]] = catsDataMonadForManaged

    def sequential: Managed[P.F, A, *] ~> Managed[M, A, *] =
      new (Managed[P.F, A, *] ~> Managed[M, A, *]) {
        def apply[B](k: Managed[P.F, A, B]): Managed[M, A, B] = k.mapK(P.sequential)
      }

    def parallel: Managed[M, A, *] ~> Managed[P.F, A, *] =
      new (Managed[M, A, *] ~> Managed[P.F, A, *]) {
        def apply[B](k: Managed[M, A, B]): Managed[P.F, A, B] = k.mapK(P.parallel)
      }
  }

}


object Managed extends ManagedInstances {

  type Finalizer[F[_]] = Resource.ExitCase => F[Any]
  object Finalizer {
    def noop[F[_]: Applicative]: Finalizer[F] = _ => Applicative[F].pure(())
  }

  def make[F[_], R, A](acquire: R => F[A])(release: A => R => F[Any]): Managed[F, R, A] = {
    ???
  }

  def fail[F[_]: MonadError[*[_], Throwable]](e: Throwable): Managed[F, Any, Nothing] = {
    eval {
      MonadError[F, Throwable].raiseError(e)
    }
  }

  def environment[F[_]: Applicative, R]: Managed[F, R, R] =
    fromFunction(identity[R])

  def succeed[F[_]: Applicative, A](r: => A): Managed[F, Any, A] = {
    fromFunction[F, Any, A](_ => r)
  }

  def succeedNow[F[_]: Applicative, A](a: A): Managed[F, Any, A] = {
    succeed(a)
  }

  def fromFunction[F[_]: Applicative, R, A](f: R => A) = {
    evalFunction(f.andThen(Applicative[F].pure))
  }

  def fromFunctionM[F[_]: MonadError[*[_], Throwable], R, A](f: R => Managed[F, Any, A]): Managed[F, R, A] = {
    fromFunction[F, R, Managed[F, R, A]](f).flatten
  }

  def evalFunction[F[_], R, A](fa: R => F[A]): Managed[F, R, A] = {
    Managed(fa.andThen(Resource.eval))
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
