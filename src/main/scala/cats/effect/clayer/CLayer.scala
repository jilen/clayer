/*
 * Copyright 2020-2021 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cats.effect.clayer

import cats._
import cats.syntax.all._
import cats.effect._

/**
 * A `CLayer[A, E, B]` describes a layer of an application: every layer in an
 * application requires some services (the input) and produces some services
 * (the output).
 *
 * Layers can be thought of as recipes for producing bundles of services, given
 * their dependencies (other services).
 *
 * Construction of layers can be effectful and utilize resources that must be
 * acquired and safely released when the services are done being utilized.
 *
 * By default layers are shared, meaning that if the same layer is used twice
 * the layer will only be allocated a single time.
 *
 * Because of their excellent composition properties, layers are the idiomatic
 * way in ZIO to create services that depend on other services.
 */
sealed abstract class CLayer[F[_], -RIn, +ROut](implicit F: Async[F], P: Parallel[F]) { self =>

  type Layer[RIn, ROut] = CLayer[F, RIn, ROut]

  private final val bundle = CLayer.bundle[F]

  final def +!+[RIn2, ROut1 >: ROut, ROut2](
    that: Layer[RIn2,  ROut2]
  )(implicit ev: Has.UnionAll[ROut1, ROut2]): Layer[RIn with RIn2,  ROut1 with ROut2] =
    self.zipWithPar(that)(ev.unionAll)

  /**
   * Combines this layer with the specified layer, producing a new layer that
   * has the inputs of both layers, and the outputs of both layers.
   */
  final def ++[RIn2, ROut1 >: ROut, ROut2](
    that: Layer[RIn2,  ROut2]
  )(implicit ev: Has.Union[ROut1, ROut2], tag: Tag[ROut2]): Layer[RIn with RIn2,  ROut1 with ROut2] =
    self.zipWithPar(that)(ev.union)

  /**
   * A symbolic alias for `zipPar`.
  */
  final def <&>[RIn2, ROut2](that: Layer[RIn2,  ROut2]): Layer[RIn with RIn2,  (ROut, ROut2)] =
    zipWithPar(that)((_, _))

  /**
   * A symbolic alias for `orElse`.
   */
  def <>[RIn1 <: RIn, ROut1 >: ROut](
    that: Layer[RIn1, ROut1]
  ): Layer[RIn1, ROut1] =
    self.orElse(that)

  /**
   * Feeds the output services of this layer into the input of the specified
   * layer, resulting in a new layer with the inputs of this layer, and the
   * outputs of both this layer and the specified layer.
   */
  final def >+>[RIn2 >: ROut, ROut1 >: ROut, ROut2](
    that: Layer[RIn2, ROut2]
  )(implicit ev: Has.Union[ROut1, ROut2], tagged: Tag[ROut2]): Layer[RIn, ROut1 with ROut2] =
    CLayer.ZipWith(self, self >>> that, ev.union)

  /**
   * Feeds the output services of this layer into the input of the specified
   * layer, resulting in a new layer with the inputs of this layer, and the
   * outputs of the specified layer.
   */
  final def >>>[ROut2](that: Layer[ROut,  ROut2]): Layer[RIn,  ROut2] =
    fold(bundle.fromFunctionManyM { case (_, cause) => F.raiseError(cause) }, that)

  /**
   * A named alias for `++`.
   */
  final def and[ RIn2, ROut1 >: ROut, ROut2](
    that: Layer[RIn2,  ROut2]
  )(implicit ev: Has.Union[ROut1, ROut2], tagged: Tag[ROut2]): Layer[RIn with RIn2,  ROut1 with ROut2] =
    self.++[ RIn2, ROut1, ROut2](that)

  /**
   * A named alias for `>+>`.
   */
  final def andTo[ RIn2 >: ROut, ROut1 >: ROut, ROut2](
    that: Layer[RIn2,  ROut2]
  )(implicit ev: Has.Union[ROut1, ROut2], tagged: Tag[ROut2]): Layer[RIn,  ROut1 with ROut2] =
    self.>+>[ RIn2, ROut1, ROut2](that)

  /**
   * Builds a layer into a managed value.
   */
  final def build: Managed[F, RIn, ROut] =
    for {
      memoMap <- Managed.eval(MemoMap.make[F])
      run     <- self.scope
      value   <- run(memoMap)
    } yield value

  /**
   * Recovers from all errors.
   */
  final def catchAll[RIn1 <: RIn,  ROut1 >: ROut](
    handler: Layer[(RIn1, Throwable),  ROut1]
  ): Layer[RIn1,  ROut1] = {

    fold(handler, bundle.identity)
  }

  /**
   * Feeds the error or output services of this layer into the input of either
   * the specified `failure` or `success` layers, resulting in a new layer with
   * the inputs of this layer, and the error or outputs of the specified layer.
   */
  final def fold[ RIn1 <: RIn, ROut2](
    failure: Layer[(RIn1, Throwable),  ROut2],
    success: Layer[ROut,  ROut2]
  ): Layer[RIn1,  ROut2] =
    CLayer.Fold(self, failure, success)

  /**
   * Creates a fresh version of this layer that will not be shared.
   */
  final def fresh: Layer[RIn, ROut] =
    CLayer.Fresh(self)

  /**
    * Returns the hash code of this layer.
    */
  override final lazy val hashCode: Int =
    super.hashCode

  /**
   * Builds this layer and uses it until it is interrupted. This is useful when
   * your entire application is a layer, such as an HTTP server.
   */
  final def launch(implicit ev: Any <:< RIn): RIn => F[Nothing] =
    build.provide(ev).useForever

  /**
   * Returns a new layer whose output is mapped by the specified function.
   */
  final def map[ROut1](f: ROut => ROut1): Layer[RIn, ROut1] =
    self >>> bundle.fromFunctionMany(f)

  /**
   * Returns a managed effect that, if evaluated, will return the lazily
   * computed result of this layer.
   */
  final def memoize: Managed[F, Any, Layer[RIn, ROut]] =
    build.memoize.map(CLayer(_))


  /**
   * Executes this layer and returns its output, if it succeeds, but otherwise
   * executes the specified layer.
   */
  final def orElse[RIn1 <: RIn,  ROut1 >: ROut](
    that: Layer[RIn1,  ROut1]
  ): Layer[RIn1,  ROut1] =
    catchAll(bundle.first >>> that)

  /**
   * Retries constructing this layer according to the specified schedule.
   */
  final def retry[RIn1 <: RIn with clock.Clock](schedule: Schedule[RIn1, E, Any]): Layer[RIn1, E, ROut] = {
    import Schedule.StepFunction
    import Schedule.Decision._

    type S = StepFunction[RIn1, E, Any]

    lazy val loop: Layer[(RIn1, S), E, ROut] =
      (bundle.first >>> self).catchAll {
        val update: Layer[((RIn1, S), E), E, (RIn1, S)] =
          bundle.fromFunctionManyM {
            case ((r, s), e) =>
              clock.currentDateTime.orDie.flatMap(now => s(now, e).flatMap {
                case Done(_) => ZIO.fail(e)
                case Continue(_, interval, next) => clock.sleep(Duration.fromInterval(now, interval)) as ((r, next))
              }).provide(r)
          }
        update >>> bundle.suspend(loop.fresh)
      }
    bundle.identity <&> bundle.fromEffectMany(ZIO.succeed(schedule.step)) >>> loop
  }

  /**
    * Performs the specified effect if this layer succeeds.
    */
  final def tap[RIn1 <: RIn, E1 >: E](f: ROut => ZIO[RIn1,  Any]): Layer[RIn1,  ROut] =
    bundle.identity <&> self >>> bundle.fromFunctionManyM { case (in, out) => f(out).provide(in) *> ZIO.succeed(out) }

  /**
   * Performs the specified effect if this layer fails.
   */
  final def tapError[RIn1 <: RIn, E1 >: E](f: E => ZIO[RIn1,  Any]): Layer[RIn1,  ROut] =
    catchAll(bundle.fromFunctionManyM { case (r, e) => f(e).provide(r) *> ZIO.fail(e) })

  /**
   * A named alias for `>>>`.
   */
  final def to[ ROut2](that: Layer[ROut,  ROut2]): Layer[RIn,  ROut2] =
    self >>> that

  /**
   * Converts a layer that requires no services into a managed runtime, which
   * can be used to execute effects.
   */
  final def toRuntime(p: Platform)(implicit ev: Any <:< RIn): Managed[E, Runtime[ROut]] =
    build.provide(ev).map(Runtime(_, p))

  /**
   * Updates one of the services output by this layer.
   */
  final def update[A: Tag](f: A => A)(implicit ev1: Has.IsHas[ROut], ev2: ROut <:< Has[A]): Layer[RIn, E, ROut] =
    self >>> bundle.fromFunctionMany(ev1.update[ROut, A](_, f))

  /**
   * Combines this layer with the specified layer, producing a new layer that
   * has the inputs of both layers, and the outputs of both layers combined
   * into a tuple.
   */
  final def zipPar[ RIn2, ROut2](that: Layer[RIn2,  ROut2]): Layer[RIn with RIn2,  (ROut, ROut2)] =
    zipWithPar(that)((_, _))

  /**
   * Combines this layer with the specified layer, producing a new layer that
   * has the inputs of both layers, and the outputs of both layers combined
   * using the specified function.
   */
  final def zipWithPar[ RIn2, ROut1 >: ROut, ROut2, ROut3](
    that: Layer[RIn2,  ROut2]
  )(f: (ROut, ROut2) => ROut3): Layer[RIn with RIn2,  ROut3] =
    bundle.ZipWithPar(self, that, f)

  /**
    * Returns whether this layer is a fresh version that will not be shared.
    */
  private final def isFresh: Boolean =
    self match {
      case bundle.Fresh(_) => true
      case _ => false
    }

  private final def scope: Managed[F, Nothing, MemoMap[F] => Managed[F, RIn, ROut]] =
    self match {
      case bundle.Fold(self, failure, success) =>
        ZManaged.succeed(memoMap =>
          memoMap
            .getOrElseMemoize(self)
            .foldCauseM(
              e => ZManaged.environment[RIn].flatMap(r => memoMap.getOrElseMemoize(failure).provide((r, e))),
              r => memoMap.getOrElseMemoize(success).provide(r)(NeedsEnv.needsEnv)
            )
        )
      case bundle.Fresh(self) =>
        Managed.succeed(_ => self.build)
      case bundle.Managed(self) =>
        Managed.succeed(_ => self)
      case bundle.Suspend(self) =>
         ZManaged.succeed(memoMap => memoMap.getOrElseMemoize(self()))
      case bundle.ZipWith(self, that, f) =>
        ZManaged.succeed(memoMap => memoMap.getOrElseMemoize(self).zipWith(memoMap.getOrElseMemoize(that))(f))
      case bundle.ZipWithPar(self, that, f) =>
        ZManaged.succeed(memoMap => memoMap.getOrElseMemoize(self).zipWithPar(memoMap.getOrElseMemoize(that))(f))
    }
}

trait LayerFunctions[F[_]] {

}

object CLayer {

  def bundle[F[_]: Async: Parallel]: Bundle[F] = new Bundle {
    def F = Async[F]
    def P = Parallel[F]
  }

  private[clayer] final case class Fold[F[_]: Async: Parallel, RIn, ROut, ROut1](
    self: CLayer[F, RIn,  ROut],
    failure: CLayer[F, (RIn, Throwable),  ROut1],
    success: CLayer[F, ROut,  ROut1]
  ) extends CLayer[F, RIn,  ROut1]
  private[clayer] final case class Fresh[F[_]: Async: Parallel, RIn,  ROut](self: CLayer[F, RIn,  ROut])        extends CLayer[F, RIn,  ROut]
  private[clayer] final case class Managed[F[_]: Async: Parallel, -RIn, +ROut](self: Managed[F, RIn,  ROut]) extends CLayer[F, RIn,  ROut]
  private[clayer] final case class Suspend[F[_]: Async: Parallel, -RIn, +ROut](self: () => CLayer[F, RIn,  ROut]) extends CLayer[F, RIn,  ROut]
  private[clayer] final case class ZipWith[F[_]: Async: Parallel, -RIn, +ROut, ROut2, ROut3](
    self: CLayer[F, RIn,  ROut],
    that: CLayer[F, RIn,  ROut2],
    f: (ROut, ROut2) => ROut3
  ) extends CLayer[F, RIn,  ROut3]
  private[clayer] final case class ZipWithPar[F[_]: Async: Parallel, -RIn, + ROut, ROut2, ROut3](
    self: CLayer[RIn,  ROut],
    that: CLayer[RIn,  ROut2],
    f: (ROut, ROut2) => ROut3
  ) extends CLayer[F, RIn,  ROut3]

  /**
   * Constructs a layer from a managed resource.
   */
  def apply[F[_]: Async: Parallel, RIn,  ROut](managed: Managed[F, RIn,  ROut]): CLayer[F, RIn,  ROut] =
    Managed(managed)

  /**
   * Constructs a layer that fails with the specified value.
   */
  def fail(e: Throwable): Layer[F, Nothing] =
    CLayer(Managed.fail(e))


  trait Bundle[F[_]] {

    implicit def F: Async[F]
    implicit def P: Parallel[F]

    /**
     * A layer that passes along the first element of a tuple.
     */
    def first[A]: CLayer[F, (A, Any), A] =
      bundle.fromFunctionMany(_._1)

    /**
     * Constructs a layer from acquire and release actions. The acquire and
     * release actions will be performed uninterruptibly.
     */
    def fromAcquireRelease[R,  A: Tag](acquire: ZIO[R,  A])(release: A => URIO[R, Any]): CLayer[R,  Has[A]] =
      fromManaged(ZManaged.make(acquire)(release))

    /**
     * Constructs a layer from acquire and release actions, which must return one
     * or more services. The acquire and release actions will be performed
     * uninterruptibly.
     */
    def fromAcquireReleaseMany[R,  A](acquire: ZIO[R,  A])(release: A => URIO[R, Any]): CLayer[R,  A] =
      fromManagedMany(ZManaged.make(acquire)(release))

    /**
     * Constructs a layer from the specified effect.
     */
    def fromEffect[R,  A: Tag](zio: ZIO[R,  A]): CLayer[R,  Has[A]] =
      fromEffectMany(zio.asService)

    /**
     * Constructs a layer from the specified effect, which must return one or
     * more services.
     */
    def fromEffectMany[R,  A](zio: ZIO[R,  A]): CLayer[R,  A] =
      CLayer(ZManaged.fromEffect(zio))

    /**
     * Constructs a layer from the environment using the specified function.
     */
    def fromFunction[A, B: Tag](f: A => B): CLayer[F, A, Has[B]] =
      fromFunctionM(a => ZIO.succeedNow(f(a)))

    /**
     * Constructs a layer from the environment using the specified effectful
     * function.
     */
    def fromFunctionM[A,  B: Tag](f: A => F[B]): CLayer[A,  Has[B]] =
      fromFunctionManaged(a => f(a).toManaged_)

    /**
     * Constructs a layer from the environment using the specified effectful
     * resourceful function.
     */
    def fromFunctionManaged[A,  B: Tag](f: A => ZManaged[Any,  B]): CLayer[A,  Has[B]] =
      fromManaged(ZManaged.fromFunctionM(f))

    /**
     * Constructs a layer from the environment using the specified function,
     * which must return one or more services.
     */
    def fromFunctionMany[A, B](f: A => B): CLayer[F, A, B] =
      fromFunctionManyM(a => ZIO.succeedNow(f(a)))

    /**
     * Constructs a layer from the environment using the specified effectful
     * function, which must return one or more services.
     */
    def fromFunctionManyM[A,  B](f: A => F[B]): CLayer[F, A,  B] =
      fromFunctionManyManaged(a => f(a).toManaged_)

    /**
     * Constructs a layer from the environment using the specified effectful
     * resourceful function, which must return one or more services.
     */
    def fromFunctionManyManaged[A,  B](f: A => Managed[F, Any,  B]): CLayer[F, A,  B] =
      CLayer {

      }


    /**
     * Constructs a layer from a managed resource.
     */
    def fromManaged[R,  A: Tag](m: ZManaged[R,  A]): CLayer[R,  Has[A]] =
      CLayer(m.asService)

    /**
     * Constructs a layer from a managed resource, which must return one or more
     * services.
     */
    def fromManagedMany[R,  A](m: ZManaged[R,  A]): CLayer[R,  A] =
      CLayer(m)

    /**
     * An identity layer that passes along its inputs.
     */
    def identity[A]: CLayer[F, A, A] =
      bundle.requires[A]

    /**
     * Constructs a layer that passes along the specified environment as an
     * output.
     */
    def requires[A]: CLayer[F, A, A] =
      CLayer(ZManaged.environment[A])

    /**
     * A layer that passes along the second element of a tuple.
     */
    def second[A]: CLayer[(Any, A), Nothing, A] =
      bundle.fromFunctionMany(_._2)

    /**
     * Constructs a layer that accesses and returns the specified service from
     * the environment.
     */
    def service[A]: CLayer[Has[A], Nothing, Has[A]] =
      CLayer(ZManaged.environment[Has[A]])

    /**
     * Constructs a layer from the specified value.
     */
    def succeed[A: Tag](a: A): ULayer[Has[A]] =
      CLayer(ZManaged.succeedNow(Has(a)))

    /**
     * Constructs a layer from the specified value, which must return one or more
     * services.
     */
    def succeedMany[A](a: A): ULayer[A] =
      CLayer(ZManaged.succeedNow(a))

    /**
     * Lazily constructs a layer. This is useful to avoid infinite recursion when
     * creating layers that refer to themselves.
     */
    def suspend[RIn,  ROut](layer: => CLayer[RIn,  ROut]): CLayer[RIn,  ROut] = {
      lazy val self = layer
      Suspend(() => self)
    }

    implicit final class CLayerPassthroughOps[RIn,  ROut](private val self: CLayer[RIn,  ROut]) extends AnyVal {

      /**
       * Returns a new layer that produces the outputs of this layer but also
       * passes through the inputs to this layer.
       */
      def passthrough(implicit ev: Has.Union[RIn, ROut], tag: Tag[ROut]): CLayer[RIn,  RIn with ROut] =
        bundle.identity[RIn] ++ self
    }

    implicit final class CLayerProjectOps[R,  A](private val self: CLayer[R,  Has[A]]) extends AnyVal {

      /**
       * Projects out part of one of the layers output by this layer using the
       * specified function
       */
      final def project[B: Tag](f: A => B)(implicit tag: Tag[A]): CLayer[R,  Has[B]] =
        self >>> bundle.fromFunction(r => f(r.get))
    }
  }
}
