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

sealed abstract class CLayer[F[_]: Raise, -RIn, +ROut] { self =>

  type FLayer[I, O] = CLayer[F, I, O]


  /**
   * A symbolic alias for `orDie`.
   */
  final def ! : FLayer[RIn, ROut] =
    self.orDie

  final def +!+[RIn2, ROut1 >: ROut, ROut2](
    that: FLayer[RIn2, ROut2]
  )(implicit ev: Has.UnionAll[ROut1, ROut2]): FLayer[RIn with RIn2, ROut1 with ROut2] =
    self.zipWithPar(that)(ev.unionAll)

  /**
   * Combines this layer with the specified layer, producing a new layer that
   * has the inputs of both layers, and the outputs of both layers.
   */
  final def ++[RIn2, ROut1 >: ROut, ROut2](
    that: FLayer[RIn2, ROut2]
  )(implicit ev: Has.Union[ROut1, ROut2], tag: Tag[ROut2]): FLayer[RIn with RIn2, ROut1 with ROut2] =
    self.zipWithPar(that)(ev.union)

  /**
   * A symbolic alias for `zipPar`.
  */
  final def <&>[RIn2, ROut2](that: FLayer[RIn2, ROut2]): FLayer[RIn with RIn2, (ROut, ROut2)] =
    zipWithPar(that)((_, _))

  /**
   * A symbolic alias for `orElse`.
   */
  def <>[RIn1 <: RIn, ROut1 >: ROut](
    that: FLayer[RIn1, ROut1]
  ): FLayer[RIn1, ROut1] =
    self.orElse(that)

  /**
   * Feeds the output services of this layer into the input of the specified
   * layer, resulting in a new layer with the inputs of this layer, and the
   * outputs of both this layer and the specified layer.
   */
  final def >+>[RIn2 >: ROut, ROut1 >: ROut, ROut2](
    that: FLayer[RIn2, ROut2]
  )(implicit ev: Has.Union[ROut1, ROut2], tagged: Tag[ROut2]): FLayer[RIn, ROut1 with ROut2] =
    CLayer.ZipWith(self, self >>> that, ev.union)

  /**
   * Feeds the output services of this layer into the input of the specified
   * layer, resulting in a new layer with the inputs of this layer, and the
   * outputs of the specified layer.
   */
  final def >>>[ROut2](that: FLayer[ROut, ROut2]): FLayer[RIn, ROut2] =
    fold(CLayer.fromFunctionManyM { case (_, cause) => Raise[F].raiseError(cause) }, that)

  /**
   * A named alias for `++`.
   */
  final def and[E1 >: E, RIn2, ROut1 >: ROut, ROut2](
    that: FLayer[RIn2, E1, ROut2]
  )(implicit ev: Has.Union[ROut1, ROut2], tagged: Tag[ROut2]): FLayer[RIn with RIn2, E1, ROut1 with ROut2] =
    self.++[E1, RIn2, ROut1, ROut2](that)

  /**
   * A named alias for `>+>`.
   */
  final def andTo[E1 >: E, RIn2 >: ROut, ROut1 >: ROut, ROut2](
    that: FLayer[RIn2, E1, ROut2]
  )(implicit ev: Has.Union[ROut1, ROut2], tagged: Tag[ROut2]): FLayer[RIn, E1, ROut1 with ROut2] =
    self.>+>[E1, RIn2, ROut1, ROut2](that)

  /**
   * Builds a layer into a managed value.
   */
  final def build: ZManaged[RIn, E, ROut] =
    for {
      memoMap <- FLayer.MemoMap.make.toManaged_
      run     <- self.scope
      value   <- run(memoMap)
    } yield value

  /**
   * Recovers from all errors.
   */
  final def catchAll[RIn1 <: RIn, E1, ROut1 >: ROut](
    handler: FLayer[(RIn1, E), E1, ROut1]
  ): FLayer[RIn1, E1, ROut1] = {
    val failureOrDie: FLayer[(RIn1, Cause[E]), Nothing, (RIn1, E)] =
      FLayer.fromFunctionManyM {
        case (r, cause) =>
          cause.failureOrCause.fold(
            e => ZIO.succeed((r, e)),
            c => ZIO.halt(c)
          )
      }
    fold(failureOrDie >>> handler, FLayer.identity)
  }

  /**
   * Feeds the error or output services of this layer into the input of either
   * the specified `failure` or `success` layers, resulting in a new layer with
   * the inputs of this layer, and the error or outputs of the specified layer.
   */
  final def fold[E1, RIn1 <: RIn, ROut2](
    failure: FLayer[(RIn1, Throwable), ROut2],
    success: FLayer[ROut, ROut2]
  ): FLayer[RIn1, ROut2] =
    CLayer.Fold(self, failure, success)

  /**
   * Creates a fresh version of this layer that will not be shared.
   */
  final def fresh: FLayer[RIn, ROut] =
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
  final def launch(implicit ev: Any <:< RIn): IO[E, Nothing] =
    build.provide(ev).useForever

  /**
   * Returns a new layer whose output is mapped by the specified function.
   */
  final def map[ROut1](f: ROut => ROut1): FLayer[RIn, E, ROut1] =
    self >>> FLayer.fromFunctionMany(f)

  /**
   * Returns a layer with its error channel mapped using the specified
   * function.
   */
  final def mapError[E1](f: E => E1)(implicit ev: CanFail[E]): FLayer[RIn, E1, ROut] =
    catchAll(FLayer.second >>> FLayer.fromFunctionManyM(e => ZIO.fail(f(e))))

  /**
   * Returns a managed effect that, if evaluated, will return the lazily
   * computed result of this layer.
   */
  final def memoize: ZManaged[Any, Nothing, FLayer[RIn, E, ROut]] =
    build.memoize.map(FLayer(_))

  /**
   * Translates effect failure into death of the fiber, making all failures
   * unchecked and not a part of the type of the layer.
   */
  final def orDie(): FLayer[RIn, ROut] =
    catchAll(FLayer.second >>> FLayer.fromFunctionManyM(ZIO.die(_)))

  /**
   * Executes this layer and returns its output, if it succeeds, but otherwise
   * executes the specified layer.
   */
  final def orElse[RIn1 <: RIn, ROut1 >: ROut](
    that: FLayer[RIn1, ROut1]
  ): FLayer[RIn1, ROut1] =
    catchAll(FLayer.first >>> that)

  /**
   * Retries constructing this layer according to the specified schedule.
   */
  final def retry[RIn1 <: RIn with clock.Clock](schedule: Schedule[RIn1, E, Any]): FLayer[RIn1, E, ROut] = {
    import Schedule.StepFunction
    import Schedule.Decision._

    type S = StepFunction[RIn1, E, Any]

    lazy val loop: FLayer[(RIn1, S), E, ROut] =
      (FLayer.first >>> self).catchAll {
        val update: FLayer[((RIn1, S), E), E, (RIn1, S)] =
          FLayer.fromFunctionManyM {
            case ((r, s), e) =>
              clock.currentDateTime.orDie.flatMap(now => s(now, e).flatMap {
                case Done(_) => ZIO.fail(e)
                case Continue(_, interval, next) => clock.sleep(Duration.fromInterval(now, interval)) as ((r, next))
              }).provide(r)
          }
        update >>> FLayer.suspend(loop.fresh)
      }
    FLayer.identity <&> FLayer.fromEffectMany(ZIO.succeed(schedule.step)) >>> loop
  }

  /**
    * Performs the specified effect if this layer succeeds.
    */
  final def tap[RIn1 <: RIn, E1 >: E](f: ROut => ZIO[RIn1, E1, Any]): FLayer[RIn1, E1, ROut] =
    FLayer.identity <&> self >>> FLayer.fromFunctionManyM { case (in, out) => f(out).provide(in) *> ZIO.succeed(out) }

  /**
   * Performs the specified effect if this layer fails.
   */
  final def tapError[RIn1 <: RIn, E1 >: E](f: E => ZIO[RIn1, E1, Any]): FLayer[RIn1, E1, ROut] =
    catchAll(FLayer.fromFunctionManyM { case (r, e) => f(e).provide(r) *> ZIO.fail(e) })

  /**
   * A named alias for `>>>`.
   */
  final def to[E1 >: E, ROut2](that: FLayer[ROut, E1, ROut2]): FLayer[RIn, E1, ROut2] =
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
  final def update[A: Tag](f: A => A)(implicit ev1: Has.IsHas[ROut], ev2: ROut <:< Has[A]): FLayer[RIn, E, ROut] =
    self >>> FLayer.fromFunctionMany(ev1.update[ROut, A](_, f))

  /**
   * Combines this layer with the specified layer, producing a new layer that
   * has the inputs of both layers, and the outputs of both layers combined
   * into a tuple.
   */
  final def zipPar[E1 >: E, RIn2, ROut2](that: FLayer[RIn2, E1, ROut2]): FLayer[RIn with RIn2, E1, (ROut, ROut2)] =
    zipWithPar(that)((_, _))

  /**
   * Combines this layer with the specified layer, producing a new layer that
   * has the inputs of both layers, and the outputs of both layers combined
   * using the specified function.
   */
  final def zipWithPar[RIn2, ROut1 >: ROut, ROut2, ROut3](
    that: FLayer[RIn2, ROut2]
  )(f: (ROut, ROut2) => ROut3): FLayer[RIn with RIn2, ROut3] =
    FLayer.ZipWithPar(self, that, f)

  /**
    * Returns whether this layer is a fresh version that will not be shared.
    */
  private final def isFresh: Boolean =
    self match {
      case FLayer.Fresh(_) => true
      case _ => false
    }

  private final def scope: Managed[Nothing, FLayer.MemoMap => ZManaged[RIn, E, ROut]] =
    self match {
      case FLayer.Fold(self, failure, success) =>
        ZManaged.succeed(memoMap =>
          memoMap
            .getOrElseMemoize(self)
            .foldCauseM(
              e => ZManaged.environment[RIn].flatMap(r => memoMap.getOrElseMemoize(failure).provide((r, e))),
              r => memoMap.getOrElseMemoize(success).provide(r)(NeedsEnv.needsEnv)
            )
        )
      case FLayer.Fresh(self) =>
        Managed.succeed(_ => self.build)
      case FLayer.Managed(self) =>
        Managed.succeed(_ => self)
      case FLayer.Suspend(self) =>
         ZManaged.succeed(memoMap => memoMap.getOrElseMemoize(self()))
      case FLayer.ZipWith(self, that, f) =>
        ZManaged.succeed(memoMap => memoMap.getOrElseMemoize(self).zipWith(memoMap.getOrElseMemoize(that))(f))
      case FLayer.ZipWithPar(self, that, f) =>
        ZManaged.succeed(memoMap => memoMap.getOrElseMemoize(self).zipWithPar(memoMap.getOrElseMemoize(that))(f))
    }
}

object CLayer {

  private final case class Fold[F[_], RIn, ROut, ROut1](
    self: CLayer[F,RIn, ROut],
    failure: CLayer[F, Throwable, ROut1],
    success: CLayer[F, ROut, ROut1]
  ) extends CLayer[F,RIn,ROut1]
  private final case class Fresh[F[_], RIn, ROut](self: CLayer[F,RIn, ROut])        extends CLayer[F,RIn, ROut]
  private final case class Managed[F[_], -RIn, +E, +ROut](self: ZManaged[RIn, ROut]) extends CLayer[F,RIn, ROut]
  private final case class Suspend[F[_], -RIn, +E, +ROut](self: () => CLayer[F,RIn, ROut]) extends CLayer[F,RIn, ROut]
  private final case class ZipWith[F[_], -RIn, +E, ROut, ROut2, ROut3](
    self: CLayer[F,RIn, ROut],
    that: CLayer[F,RIn, ROut2],
    f: (ROut, ROut2) => ROut3
  ) extends CLayer[F,RIn, ROut3]
  private final case class ZipWithPar[F[_], -RIn, +E, ROut, ROut2, ROut3](
    self: CLayer[F,RIn, ROut],
    that: CLayer[F,RIn, ROut2],
    f: (ROut, ROut2) => ROut3
  ) extends CLayer[F,RIn, ROut3]

  /**
   * Constructs a layer from a managed resource.
   */
  def apply[RIn, ROut](managed: ZManaged[RIn, ROut]): CLayer[F,RIn, ROut] =
    Managed(managed)

  /**
   * Constructs a layer that fails with the specified value.
   */
  def fail[E](e: E): Layer[E, Nothing] =
    FLayer(ZManaged.fail(e))

  /**
   * A layer that passes along the first element of a tuple.
   */
  def first[A]: CLayer[F,(A, Any), Nothing, A] =
    FLayer.fromFunctionMany(_._1)

  /**
   * Constructs a layer from acquire and release actions. The acquire and
   * release actions will be performed uninterruptibly.
   */
  def fromAcquireRelease[R, A: Tag](acquire: ZIO[R, A])(release: A => URIO[R, Any]): CLayer[F,R, Has[A]] =
    fromManaged(ZManaged.make(acquire)(release))

  /**
   * Constructs a layer from acquire and release actions, which must return one
   * or more services. The acquire and release actions will be performed
   * uninterruptibly.
   */
  def fromAcquireReleaseMany[R, A](acquire: ZIO[R, A])(release: A => URIO[R, Any]): CLayer[F,R, A] =
    fromManagedMany(ZManaged.make(acquire)(release))

  /**
   * Constructs a layer from the specified effect.
   */
  def fromEffect[R, A: Tag](zio: ZIO[R, A]): CLayer[F,R, Has[A]] =
    fromEffectMany(zio.asService)

  /**
   * Constructs a layer from the specified effect, which must return one or
   * more services.
   */
  def fromEffectMany[R, A](zio: ZIO[R, A]): CLayer[F,R, A] =
    FLayer(ZManaged.fromEffect(zio))

  /**
   * Constructs a layer from the environment using the specified function.
   */
  def fromFunction[A, B: Tag](f: A => B): CLayer[F,A, Nothing, Has[B]] =
    fromFunctionM(a => ZIO.succeedNow(f(a)))

  /**
   * Constructs a layer from the environment using the specified effectful
   * function.
   */
  def fromFunctionM[A, B: Tag](f: A => IO[E, B]): CLayer[F,A, Has[B]] =
    fromFunctionManaged(a => f(a).toManaged_)

  /**
   * Constructs a layer from the environment using the specified effectful
   * resourceful function.
   */
  def fromFunctionManaged[A, B: Tag](f: A => ZManaged[Any, B]): CLayer[F,A, Has[B]] =
    fromManaged(ZManaged.fromFunctionM(f))

  /**
   * Constructs a layer from the environment using the specified function,
   * which must return one or more services.
   */
  def fromFunctionMany[A, B](f: A => B): CLayer[F,A, Nothing, B] =
    fromFunctionManyM(a => ZIO.succeedNow(f(a)))

  /**
   * Constructs a layer from the environment using the specified effectful
   * function, which must return one or more services.
   */
  def fromFunctionManyM[A, B](f: A => IO[E, B]): CLayer[F,A, B] =
    fromFunctionManyManaged(a => f(a).toManaged_)

  /**
   * Constructs a layer from the environment using the specified effectful
   * resourceful function, which must return one or more services.
   */
  def fromFunctionManyManaged[A, B](f: A => ZManaged[Any, B]): CLayer[F,A, B] =
    FLayer(ZManaged.fromFunctionM(f))

  /**
   * Constructs a layer that purely depends on the specified service.
   */
  def fromService[A: Tag, B: Tag](f: A => B): CLayer[F,Has[A], Nothing, Has[B]] =
    fromServiceM[A, Any, Nothing, B](a => ZIO.succeedNow(f(a)))

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, B: Tag](
    f: (A0, A1) => B
  ): CLayer[F,Has[A0] with Has[A1], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, B: Tag](
    f: (A0, A1, A2) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, A3: Tag, B: Tag](
    f: (A0, A1, A2, A3) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2] with Has[A3], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, B: Tag](
    f: (A0, A1, A2, A3, A4) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, B: Tag](
    f: (A0, A1, A2, A3, A4, A5) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, A19: Tag, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18] with Has[A19], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, A19: Tag, A20: Tag, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18] with Has[A19] with Has[A20], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, A19: Tag, A20: Tag, A21: Tag, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18] with Has[A19] with Has[A20] with Has[A21], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified service.
   */
  def fromServiceM[A: Tag, R, B: Tag](f: A => ZIO[R, B]): CLayer[F,R with Has[A], Has[B]] =
    fromServiceManaged(a => f(a).toManaged_)

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, R, B: Tag](
    f: (A0, A1) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1], Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, R, B: Tag](
    f: (A0, A1, A2) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2], Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, R, B: Tag](
    f: (A0, A1, A2, A3) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3], Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, R, B: Tag](
    f: (A0, A1, A2, A3, A4) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4], Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, R, B: Tag](
    f: (A0, A1, A2, A3, A4, A5) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5], Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, R, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6], Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, R, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7], Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, R, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8], Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, R, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9], Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, R, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10], Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, R, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11], Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, R, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12], Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, R, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13], Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, R, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14], Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, R, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15], Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, R, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16], Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, R, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17], Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, R, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18], Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, A19: Tag, R, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18] with Has[A19], Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, A19: Tag, A20: Tag, R, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18] with Has[A19] with Has[A20], Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, A19: Tag, A20: Tag, A21: Tag, R, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18] with Has[A19] with Has[A20] with Has[A21], Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified service.
   */
  def fromServiceManaged[A: Tag, R, B: Tag](f: A => ZManaged[R, B]): CLayer[F,R with Has[A], Has[B]] =
    fromServiceManyManaged(a => f(a).asService)

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, R, B: Tag](
    f: (A0, A1) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1], Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, R, B: Tag](
    f: (A0, A1, A2) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2], Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, R, B: Tag](
    f: (A0, A1, A2, A3) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3], Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, R, B: Tag](
    f: (A0, A1, A2, A3, A4) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4], Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, R, B: Tag](
    f: (A0, A1, A2, A3, A4, A5) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5], Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, R, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6], Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, R, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7], Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, R, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8], Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, R, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9], Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, R, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10], Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, R, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11], Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, R, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12], Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, R, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13], Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, R, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14], Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, R, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15], Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, R, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16], Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, R, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17], Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, R, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18], Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, A19: Tag, R, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18] with Has[A19], Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, A19: Tag, A20: Tag, R, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18] with Has[A19] with Has[A20], Has[B]] =
    fromServicesManyManaged[A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, R, Has[B]]((a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19, a20) => f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19, a20).asService)

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, A19: Tag, A20: Tag, A21: Tag, R, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18] with Has[A19] with Has[A20] with Has[A21], Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified service, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromService`.
   */
  def fromServiceMany[A: Tag, B](f: A => B): CLayer[F,Has[A], Nothing, B] =
    fromServiceManyM[A, Any, Nothing, B](a => ZIO.succeedNow(f(a)))

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, B](
    f: (A0, A1) => B
  ): CLayer[F,Has[A0] with Has[A1], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, B](
    f: (A0, A1, A2) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, A3: Tag, B](
    f: (A0, A1, A2, A3) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2] with Has[A3], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, B](
    f: (A0, A1, A2, A3, A4) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, B](
    f: (A0, A1, A2, A3, A4, A5) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, B](
    f: (A0, A1, A2, A3, A4, A5, A6) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, A19: Tag, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18] with Has[A19], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, A19: Tag, A20: Tag, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18] with Has[A19] with Has[A20], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, A19: Tag, A20: Tag, A21: Tag, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21) => B
  ): CLayer[F,Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18] with Has[A19] with Has[A20] with Has[A21], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified service,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServiceM`.
   */
  def fromServiceManyM[A: Tag, R, B](f: A => ZIO[R, B]): CLayer[F,R with Has[A], B] =
    fromServiceManyManaged(a => f(a).toManaged_)

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, R, B](
    f: (A0, A1) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1], B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, R, B](
    f: (A0, A1, A2) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2], B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, R, B](
    f: (A0, A1, A2, A3) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3], B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, R, B](
    f: (A0, A1, A2, A3, A4) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4], B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, R, B](
    f: (A0, A1, A2, A3, A4, A5) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5], B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, R, B](
    f: (A0, A1, A2, A3, A4, A5, A6) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6], B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, R, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7], B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, R, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8], B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, R, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9], B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, R, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10], B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, R, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11], B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, R, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12], B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, R, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13], B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, R, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14], B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, R, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15], B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, R, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16], B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, R, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17], B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, R, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18], B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, A19: Tag, R, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18] with Has[A19], B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, A19: Tag, A20: Tag, R, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18] with Has[A19] with Has[A20], B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, A19: Tag, A20: Tag, A21: Tag, R, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21) => ZIO[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18] with Has[A19] with Has[A20] with Has[A21], B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified service, which must return one or more services. For the more
   * common variant that returns a single service see `fromServiceManaged`.
   */
  def fromServiceManyManaged[A: Tag, R, B](f: A => ZManaged[R, B]): CLayer[F,R with Has[A], B] =
    FLayer(ZManaged.accessManaged[R with Has[A]](m => f(m.get[A])))

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, R, B](
    f: (A0, A1) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1], B] =
    FLayer {
      for {
        a0 <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1 <- ZManaged.environment[Has[A1]].map(_.get[A1])
        b  <- f(a0, a1)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, R, B](
    f: (A0, A1, A2) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2], B] =
    FLayer {
      for {
        a0 <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1 <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2 <- ZManaged.environment[Has[A2]].map(_.get[A2])
        b  <- f(a0, a1, a2)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, R, B](
    f: (A0, A1, A2, A3) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3], B] =
    FLayer {
      for {
        a0 <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1 <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2 <- ZManaged.environment[Has[A2]].map(_.get[A2])
        a3 <- ZManaged.environment[Has[A3]].map(_.get[A3])
        b  <- f(a0, a1, a2, a3)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, R, B](
    f: (A0, A1, A2, A3, A4) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4], B] =
    FLayer {
      for {
        a0 <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1 <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2 <- ZManaged.environment[Has[A2]].map(_.get[A2])
        a3 <- ZManaged.environment[Has[A3]].map(_.get[A3])
        a4 <- ZManaged.environment[Has[A4]].map(_.get[A4])
        b  <- f(a0, a1, a2, a3, a4)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, R, B](
    f: (A0, A1, A2, A3, A4, A5) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5], B] =
    FLayer {
      for {
        a0 <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1 <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2 <- ZManaged.environment[Has[A2]].map(_.get[A2])
        a3 <- ZManaged.environment[Has[A3]].map(_.get[A3])
        a4 <- ZManaged.environment[Has[A4]].map(_.get[A4])
        a5 <- ZManaged.environment[Has[A5]].map(_.get[A5])
        b  <- f(a0, a1, a2, a3, a4, a5)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, R, B](
    f: (A0, A1, A2, A3, A4, A5, A6) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6], B] =
    FLayer {
      for {
        a0 <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1 <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2 <- ZManaged.environment[Has[A2]].map(_.get[A2])
        a3 <- ZManaged.environment[Has[A3]].map(_.get[A3])
        a4 <- ZManaged.environment[Has[A4]].map(_.get[A4])
        a5 <- ZManaged.environment[Has[A5]].map(_.get[A5])
        a6 <- ZManaged.environment[Has[A6]].map(_.get[A6])
        b  <- f(a0, a1, a2, a3, a4, a5, a6)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, R, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7], B] =
    FLayer {
      for {
        a0 <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1 <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2 <- ZManaged.environment[Has[A2]].map(_.get[A2])
        a3 <- ZManaged.environment[Has[A3]].map(_.get[A3])
        a4 <- ZManaged.environment[Has[A4]].map(_.get[A4])
        a5 <- ZManaged.environment[Has[A5]].map(_.get[A5])
        a6 <- ZManaged.environment[Has[A6]].map(_.get[A6])
        a7 <- ZManaged.environment[Has[A7]].map(_.get[A7])
        b  <- f(a0, a1, a2, a3, a4, a5, a6, a7)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, R, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8], B] =
    FLayer {
      for {
        a0 <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1 <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2 <- ZManaged.environment[Has[A2]].map(_.get[A2])
        a3 <- ZManaged.environment[Has[A3]].map(_.get[A3])
        a4 <- ZManaged.environment[Has[A4]].map(_.get[A4])
        a5 <- ZManaged.environment[Has[A5]].map(_.get[A5])
        a6 <- ZManaged.environment[Has[A6]].map(_.get[A6])
        a7 <- ZManaged.environment[Has[A7]].map(_.get[A7])
        a8 <- ZManaged.environment[Has[A8]].map(_.get[A8])
        b  <- f(a0, a1, a2, a3, a4, a5, a6, a7, a8)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, R, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9], B] =
    FLayer {
      for {
        a0 <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1 <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2 <- ZManaged.environment[Has[A2]].map(_.get[A2])
        a3 <- ZManaged.environment[Has[A3]].map(_.get[A3])
        a4 <- ZManaged.environment[Has[A4]].map(_.get[A4])
        a5 <- ZManaged.environment[Has[A5]].map(_.get[A5])
        a6 <- ZManaged.environment[Has[A6]].map(_.get[A6])
        a7 <- ZManaged.environment[Has[A7]].map(_.get[A7])
        a8 <- ZManaged.environment[Has[A8]].map(_.get[A8])
        a9 <- ZManaged.environment[Has[A9]].map(_.get[A9])
        b  <- f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, R, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10], B] =
    FLayer {
      for {
        a0  <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1  <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2  <- ZManaged.environment[Has[A2]].map(_.get[A2])
        a3  <- ZManaged.environment[Has[A3]].map(_.get[A3])
        a4  <- ZManaged.environment[Has[A4]].map(_.get[A4])
        a5  <- ZManaged.environment[Has[A5]].map(_.get[A5])
        a6  <- ZManaged.environment[Has[A6]].map(_.get[A6])
        a7  <- ZManaged.environment[Has[A7]].map(_.get[A7])
        a8  <- ZManaged.environment[Has[A8]].map(_.get[A8])
        a9  <- ZManaged.environment[Has[A9]].map(_.get[A9])
        a10 <- ZManaged.environment[Has[A10]].map(_.get[A10])
        b   <- f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, R, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11], B] =
    FLayer {
      for {
        a0  <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1  <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2  <- ZManaged.environment[Has[A2]].map(_.get[A2])
        a3  <- ZManaged.environment[Has[A3]].map(_.get[A3])
        a4  <- ZManaged.environment[Has[A4]].map(_.get[A4])
        a5  <- ZManaged.environment[Has[A5]].map(_.get[A5])
        a6  <- ZManaged.environment[Has[A6]].map(_.get[A6])
        a7  <- ZManaged.environment[Has[A7]].map(_.get[A7])
        a8  <- ZManaged.environment[Has[A8]].map(_.get[A8])
        a9  <- ZManaged.environment[Has[A9]].map(_.get[A9])
        a10 <- ZManaged.environment[Has[A10]].map(_.get[A10])
        a11 <- ZManaged.environment[Has[A11]].map(_.get[A11])
        b   <- f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, R, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12], B] =
    FLayer {
      for {
        a0  <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1  <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2  <- ZManaged.environment[Has[A2]].map(_.get[A2])
        a3  <- ZManaged.environment[Has[A3]].map(_.get[A3])
        a4  <- ZManaged.environment[Has[A4]].map(_.get[A4])
        a5  <- ZManaged.environment[Has[A5]].map(_.get[A5])
        a6  <- ZManaged.environment[Has[A6]].map(_.get[A6])
        a7  <- ZManaged.environment[Has[A7]].map(_.get[A7])
        a8  <- ZManaged.environment[Has[A8]].map(_.get[A8])
        a9  <- ZManaged.environment[Has[A9]].map(_.get[A9])
        a10 <- ZManaged.environment[Has[A10]].map(_.get[A10])
        a11 <- ZManaged.environment[Has[A11]].map(_.get[A11])
        a12 <- ZManaged.environment[Has[A12]].map(_.get[A12])
        b   <- f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, R, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13], B] =
    FLayer {
      for {
        a0  <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1  <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2  <- ZManaged.environment[Has[A2]].map(_.get[A2])
        a3  <- ZManaged.environment[Has[A3]].map(_.get[A3])
        a4  <- ZManaged.environment[Has[A4]].map(_.get[A4])
        a5  <- ZManaged.environment[Has[A5]].map(_.get[A5])
        a6  <- ZManaged.environment[Has[A6]].map(_.get[A6])
        a7  <- ZManaged.environment[Has[A7]].map(_.get[A7])
        a8  <- ZManaged.environment[Has[A8]].map(_.get[A8])
        a9  <- ZManaged.environment[Has[A9]].map(_.get[A9])
        a10 <- ZManaged.environment[Has[A10]].map(_.get[A10])
        a11 <- ZManaged.environment[Has[A11]].map(_.get[A11])
        a12 <- ZManaged.environment[Has[A12]].map(_.get[A12])
        a13 <- ZManaged.environment[Has[A13]].map(_.get[A13])
        b   <- f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, R, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14], B] =
    FLayer {
      for {
        a0  <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1  <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2  <- ZManaged.environment[Has[A2]].map(_.get[A2])
        a3  <- ZManaged.environment[Has[A3]].map(_.get[A3])
        a4  <- ZManaged.environment[Has[A4]].map(_.get[A4])
        a5  <- ZManaged.environment[Has[A5]].map(_.get[A5])
        a6  <- ZManaged.environment[Has[A6]].map(_.get[A6])
        a7  <- ZManaged.environment[Has[A7]].map(_.get[A7])
        a8  <- ZManaged.environment[Has[A8]].map(_.get[A8])
        a9  <- ZManaged.environment[Has[A9]].map(_.get[A9])
        a10 <- ZManaged.environment[Has[A10]].map(_.get[A10])
        a11 <- ZManaged.environment[Has[A11]].map(_.get[A11])
        a12 <- ZManaged.environment[Has[A12]].map(_.get[A12])
        a13 <- ZManaged.environment[Has[A13]].map(_.get[A13])
        a14 <- ZManaged.environment[Has[A14]].map(_.get[A14])
        b   <- f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, R, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15], B] =
    FLayer {
      for {
        a0  <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1  <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2  <- ZManaged.environment[Has[A2]].map(_.get[A2])
        a3  <- ZManaged.environment[Has[A3]].map(_.get[A3])
        a4  <- ZManaged.environment[Has[A4]].map(_.get[A4])
        a5  <- ZManaged.environment[Has[A5]].map(_.get[A5])
        a6  <- ZManaged.environment[Has[A6]].map(_.get[A6])
        a7  <- ZManaged.environment[Has[A7]].map(_.get[A7])
        a8  <- ZManaged.environment[Has[A8]].map(_.get[A8])
        a9  <- ZManaged.environment[Has[A9]].map(_.get[A9])
        a10 <- ZManaged.environment[Has[A10]].map(_.get[A10])
        a11 <- ZManaged.environment[Has[A11]].map(_.get[A11])
        a12 <- ZManaged.environment[Has[A12]].map(_.get[A12])
        a13 <- ZManaged.environment[Has[A13]].map(_.get[A13])
        a14 <- ZManaged.environment[Has[A14]].map(_.get[A14])
        a15 <- ZManaged.environment[Has[A15]].map(_.get[A15])
        b   <- f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, R, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16], B] =
    FLayer {
      for {
        a0  <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1  <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2  <- ZManaged.environment[Has[A2]].map(_.get[A2])
        a3  <- ZManaged.environment[Has[A3]].map(_.get[A3])
        a4  <- ZManaged.environment[Has[A4]].map(_.get[A4])
        a5  <- ZManaged.environment[Has[A5]].map(_.get[A5])
        a6  <- ZManaged.environment[Has[A6]].map(_.get[A6])
        a7  <- ZManaged.environment[Has[A7]].map(_.get[A7])
        a8  <- ZManaged.environment[Has[A8]].map(_.get[A8])
        a9  <- ZManaged.environment[Has[A9]].map(_.get[A9])
        a10 <- ZManaged.environment[Has[A10]].map(_.get[A10])
        a11 <- ZManaged.environment[Has[A11]].map(_.get[A11])
        a12 <- ZManaged.environment[Has[A12]].map(_.get[A12])
        a13 <- ZManaged.environment[Has[A13]].map(_.get[A13])
        a14 <- ZManaged.environment[Has[A14]].map(_.get[A14])
        a15 <- ZManaged.environment[Has[A15]].map(_.get[A15])
        a16 <- ZManaged.environment[Has[A16]].map(_.get[A16])
        b   <- f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, R, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17], B] =
    FLayer {
      for {
        a0  <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1  <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2  <- ZManaged.environment[Has[A2]].map(_.get[A2])
        a3  <- ZManaged.environment[Has[A3]].map(_.get[A3])
        a4  <- ZManaged.environment[Has[A4]].map(_.get[A4])
        a5  <- ZManaged.environment[Has[A5]].map(_.get[A5])
        a6  <- ZManaged.environment[Has[A6]].map(_.get[A6])
        a7  <- ZManaged.environment[Has[A7]].map(_.get[A7])
        a8  <- ZManaged.environment[Has[A8]].map(_.get[A8])
        a9  <- ZManaged.environment[Has[A9]].map(_.get[A9])
        a10 <- ZManaged.environment[Has[A10]].map(_.get[A10])
        a11 <- ZManaged.environment[Has[A11]].map(_.get[A11])
        a12 <- ZManaged.environment[Has[A12]].map(_.get[A12])
        a13 <- ZManaged.environment[Has[A13]].map(_.get[A13])
        a14 <- ZManaged.environment[Has[A14]].map(_.get[A14])
        a15 <- ZManaged.environment[Has[A15]].map(_.get[A15])
        a16 <- ZManaged.environment[Has[A16]].map(_.get[A16])
        a17 <- ZManaged.environment[Has[A17]].map(_.get[A17])
        b   <- f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, R, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18], B] =
    FLayer {
      for {
        a0  <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1  <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2  <- ZManaged.environment[Has[A2]].map(_.get[A2])
        a3  <- ZManaged.environment[Has[A3]].map(_.get[A3])
        a4  <- ZManaged.environment[Has[A4]].map(_.get[A4])
        a5  <- ZManaged.environment[Has[A5]].map(_.get[A5])
        a6  <- ZManaged.environment[Has[A6]].map(_.get[A6])
        a7  <- ZManaged.environment[Has[A7]].map(_.get[A7])
        a8  <- ZManaged.environment[Has[A8]].map(_.get[A8])
        a9  <- ZManaged.environment[Has[A9]].map(_.get[A9])
        a10 <- ZManaged.environment[Has[A10]].map(_.get[A10])
        a11 <- ZManaged.environment[Has[A11]].map(_.get[A11])
        a12 <- ZManaged.environment[Has[A12]].map(_.get[A12])
        a13 <- ZManaged.environment[Has[A13]].map(_.get[A13])
        a14 <- ZManaged.environment[Has[A14]].map(_.get[A14])
        a15 <- ZManaged.environment[Has[A15]].map(_.get[A15])
        a16 <- ZManaged.environment[Has[A16]].map(_.get[A16])
        a17 <- ZManaged.environment[Has[A17]].map(_.get[A17])
        a18 <- ZManaged.environment[Has[A18]].map(_.get[A18])
        b   <- f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, A19: Tag, R, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18] with Has[A19], B] =
    FLayer {
      for {
        a0  <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1  <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2  <- ZManaged.environment[Has[A2]].map(_.get[A2])
        a3  <- ZManaged.environment[Has[A3]].map(_.get[A3])
        a4  <- ZManaged.environment[Has[A4]].map(_.get[A4])
        a5  <- ZManaged.environment[Has[A5]].map(_.get[A5])
        a6  <- ZManaged.environment[Has[A6]].map(_.get[A6])
        a7  <- ZManaged.environment[Has[A7]].map(_.get[A7])
        a8  <- ZManaged.environment[Has[A8]].map(_.get[A8])
        a9  <- ZManaged.environment[Has[A9]].map(_.get[A9])
        a10 <- ZManaged.environment[Has[A10]].map(_.get[A10])
        a11 <- ZManaged.environment[Has[A11]].map(_.get[A11])
        a12 <- ZManaged.environment[Has[A12]].map(_.get[A12])
        a13 <- ZManaged.environment[Has[A13]].map(_.get[A13])
        a14 <- ZManaged.environment[Has[A14]].map(_.get[A14])
        a15 <- ZManaged.environment[Has[A15]].map(_.get[A15])
        a16 <- ZManaged.environment[Has[A16]].map(_.get[A16])
        a17 <- ZManaged.environment[Has[A17]].map(_.get[A17])
        a18 <- ZManaged.environment[Has[A18]].map(_.get[A18])
        a19 <- ZManaged.environment[Has[A19]].map(_.get[A19])
        b   <- f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, A19: Tag, A20: Tag, R, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18] with Has[A19] with Has[A20], B] =
    FLayer {
      for {
        a0  <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1  <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2  <- ZManaged.environment[Has[A2]].map(_.get[A2])
        a3  <- ZManaged.environment[Has[A3]].map(_.get[A3])
        a4  <- ZManaged.environment[Has[A4]].map(_.get[A4])
        a5  <- ZManaged.environment[Has[A5]].map(_.get[A5])
        a6  <- ZManaged.environment[Has[A6]].map(_.get[A6])
        a7  <- ZManaged.environment[Has[A7]].map(_.get[A7])
        a8  <- ZManaged.environment[Has[A8]].map(_.get[A8])
        a9  <- ZManaged.environment[Has[A9]].map(_.get[A9])
        a10 <- ZManaged.environment[Has[A10]].map(_.get[A10])
        a11 <- ZManaged.environment[Has[A11]].map(_.get[A11])
        a12 <- ZManaged.environment[Has[A12]].map(_.get[A12])
        a13 <- ZManaged.environment[Has[A13]].map(_.get[A13])
        a14 <- ZManaged.environment[Has[A14]].map(_.get[A14])
        a15 <- ZManaged.environment[Has[A15]].map(_.get[A15])
        a16 <- ZManaged.environment[Has[A16]].map(_.get[A16])
        a17 <- ZManaged.environment[Has[A17]].map(_.get[A17])
        a18 <- ZManaged.environment[Has[A18]].map(_.get[A18])
        a19 <- ZManaged.environment[Has[A19]].map(_.get[A19])
        a20 <- ZManaged.environment[Has[A20]].map(_.get[A20])
        b   <- f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19, a20)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, A19: Tag, A20: Tag, A21: Tag, R, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21) => ZManaged[R, B]
  ): CLayer[F,R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18] with Has[A19] with Has[A20] with Has[A21], B] =
    FLayer {
      for {
        a0  <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1  <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2  <- ZManaged.environment[Has[A2]].map(_.get[A2])
        a3  <- ZManaged.environment[Has[A3]].map(_.get[A3])
        a4  <- ZManaged.environment[Has[A4]].map(_.get[A4])
        a5  <- ZManaged.environment[Has[A5]].map(_.get[A5])
        a6  <- ZManaged.environment[Has[A6]].map(_.get[A6])
        a7  <- ZManaged.environment[Has[A7]].map(_.get[A7])
        a8  <- ZManaged.environment[Has[A8]].map(_.get[A8])
        a9  <- ZManaged.environment[Has[A9]].map(_.get[A9])
        a10 <- ZManaged.environment[Has[A10]].map(_.get[A10])
        a11 <- ZManaged.environment[Has[A11]].map(_.get[A11])
        a12 <- ZManaged.environment[Has[A12]].map(_.get[A12])
        a13 <- ZManaged.environment[Has[A13]].map(_.get[A13])
        a14 <- ZManaged.environment[Has[A14]].map(_.get[A14])
        a15 <- ZManaged.environment[Has[A15]].map(_.get[A15])
        a16 <- ZManaged.environment[Has[A16]].map(_.get[A16])
        a17 <- ZManaged.environment[Has[A17]].map(_.get[A17])
        a18 <- ZManaged.environment[Has[A18]].map(_.get[A18])
        a19 <- ZManaged.environment[Has[A19]].map(_.get[A19])
        a20 <- ZManaged.environment[Has[A20]].map(_.get[A20])
        a21 <- ZManaged.environment[Has[A21]].map(_.get[A21])
        b   <- f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19, a20, a21)
      } yield b
    }

  /**
   * Constructs a layer from a managed resource.
   */
  def fromManaged[R, A: Tag](m: ZManaged[R, A]): CLayer[F,R, Has[A]] =
    FLayer(m.asService)

  /**
   * Constructs a layer from a managed resource, which must return one or more
   * services.
   */
  def fromManagedMany[R, A](m: ZManaged[R, A]): CLayer[F,R, A] =
    FLayer(m)

  /**
   * An identity layer that passes along its inputs.
   */
  def identity[A]: CLayer[F,A, Nothing, A] =
    FLayer.requires[A]

  /**
   * Constructs a layer that passes along the specified environment as an
   * output.
   */
  def requires[A]: CLayer[F,A, Nothing, A] =
    FLayer(ZManaged.environment[A])

  /**
   * A layer that passes along the second element of a tuple.
   */
  def second[A]: CLayer[F,(Any, A), Nothing, A] =
    FLayer.fromFunctionMany(_._2)

  /**
   * Constructs a layer that accesses and returns the specified service from
   * the environment.
   */
  def service[A]: CLayer[F,Has[A], Nothing, Has[A]] =
    FLayer(ZManaged.environment[Has[A]])

  /**
   * Constructs a layer from the specified value.
   */
  def succeed[A: Tag](a: A): ULayer[Has[A]] =
    FLayer(ZManaged.succeedNow(Has(a)))

  /**
   * Constructs a layer from the specified value, which must return one or more
   * services.
   */
  def succeedMany[A](a: A): ULayer[A] =
    FLayer(ZManaged.succeedNow(a))

  /**
    * Lazily constructs a layer. This is useful to avoid infinite recursion when
    * creating layers that refer to themselves.
    */
   def suspend[RIn, ROut](layer: => CLayer[F,RIn, ROut]): CLayer[F,RIn, ROut] = {
     lazy val self = layer
     Suspend(() => self)
   }

  implicit final class FLayerPassthroughOps[F[_], RIn, ROut](private val self: CLayer[F, RIn, ROut]) extends AnyVal {

    /**
     * Returns a new layer that produces the outputs of this layer but also
     * passes through the inputs to this layer.
     */
    def passthrough(implicit ev: Has.Union[RIn, ROut], tag: Tag[ROut]): CLayer[F,RIn, RIn with ROut] =
      FLayer.identity[RIn] ++ self
  }

  implicit final class FLayerProjectOps[F[_], R, A](private val self: CLayer[F, R, Has[A]]) extends AnyVal {

    /**
     * Projects out part of one of the layers output by this layer using the
     * specified function
     */
    final def project[B: Tag](f: A => B)(implicit tag: Tag[A]): CLayer[F, R, Has[B]] =
      self >>> FLayer.fromFunction(r => f(r.get))
  }

  /**
   * A `MemoMap` memoizes dependencies.
   */
  private abstract class MemoMap { self =>

    /**
     * Checks the memo map to see if a dependency exists. If it is, immediately
     * returns it. Otherwise, obtains the dependency, stores it in the memo map,
     * and adds a finalizer to the outer `Managed`.
     */
    def getOrElseMemoize[E, A, B](layer: CLayer[F,A, B]): ZManaged[A, B]
  }

  private object MemoMap {

    /**
     * Constructs an empty memo map.
     */
    def make: UIO[MemoMap] =
      RefM
        .make[Map[CLayer[F,Nothing, Any, Any], (IO[Any, Any], ZManaged.Finalizer)]](Map.empty)
        .map { ref =>
          new MemoMap { self =>
            final def getOrElseMemoize[E, A, B](layer: CLayer[F,A, B]): ZManaged[A, B] =
              ZManaged {
                ref.modify { map =>
                  map.get(layer) match {
                    case Some((acquire, release)) =>
                      val cached =
                        ZIO.accessM[(A, ReleaseMap)] {
                          case (_, releaseMap) =>
                            acquire
                              .asInstanceOf[IO[E, B]]
                              .onExit {
                                case Exit.Success(_) => releaseMap.add(release)
                                case Exit.Failure(_) => UIO.unit
                              }
                              .map((release, _))
                        }

                      UIO.succeed((cached, map))

                    case None =>
                      for {
                        observers    <- Ref.make(0)
                        promise      <- Promise.make[E, B]
                        finalizerRef <- Ref.make[ZManaged.Finalizer](ZManaged.Finalizer.noop)

                        resource = ZIO.uninterruptibleMask { restore =>
                          for {
                            env                  <- ZIO.environment[(A, ReleaseMap)]
                            (a, outerReleaseMap) = env
                            innerReleaseMap      <- ZManaged.ReleaseMap.make
                            tp <- restore(layer.scope.flatMap(_.apply(self)).zio.provide((a, innerReleaseMap))).run.flatMap {
                                   case e @ Exit.Failure(cause) =>
                                     promise.halt(cause) *> innerReleaseMap.releaseAll(e, ExecutionStrategy.Sequential) *> ZIO
                                       .halt(cause)

                                   case Exit.Success((_, b)) =>
                                     for {
                                       _ <- finalizerRef.set { (e: Exit[Any, Any]) =>
                                             ZIO.whenM(observers.modify(n => (n == 1, n - 1)))(
                                               innerReleaseMap.releaseAll(e, ExecutionStrategy.Sequential)
                                             )
                                           }
                                       _              <- observers.update(_ + 1)
                                       outerFinalizer <- outerReleaseMap.add(e => finalizerRef.get.flatMap(_.apply(e)))
                                       _              <- promise.succeed(b)
                                     } yield (outerFinalizer, b)
                                 }
                          } yield tp
                        }

                        memoized = (
                          promise.await.onExit {
                            case Exit.Failure(_) => UIO.unit
                            case Exit.Success(_) => observers.update(_ + 1)
                          },
                          (exit: Exit[Any, Any]) => finalizerRef.get.flatMap(_(exit))
                        )
                      } yield (resource, if (layer.isFresh) map else map + (layer -> memoized))

                  }
                }.flatten
              }
          }
        }
  }

  private def andThen[A0, A1, B, C](f: (A0, A1) => B)(g: B => C): (A0, A1) => C =
    (a0, a1) => g(f(a0, a1))

  private def andThen[A0, A1, A2, B, C](f: (A0, A1, A2) => B)(g: B => C): (A0, A1, A2) => C =
    (a0, a1, a2) => g(f(a0, a1, a2))

  private def andThen[A0, A1, A2, A3, B, C](f: (A0, A1, A2, A3) => B)(g: B => C): (A0, A1, A2, A3) => C =
    (a0, a1, a2, a3) => g(f(a0, a1, a2, a3))

  private def andThen[A0, A1, A2, A3, A4, B, C](f: (A0, A1, A2, A3, A4) => B)(g: B => C): (A0, A1, A2, A3, A4) => C =
    (a0, a1, a2, a3, a4) => g(f(a0, a1, a2, a3, a4))

  private def andThen[A0, A1, A2, A3, A4, A5, B, C](f: (A0, A1, A2, A3, A4, A5) => B)(g: B => C): (A0, A1, A2, A3, A4, A5) => C =
    (a0, a1, a2, a3, a4, a5) => g(f(a0, a1, a2, a3, a4, a5))

  private def andThen[A0, A1, A2, A3, A4, A5, A6, B, C](f: (A0, A1, A2, A3, A4, A5, A6) => B)(g: B => C): (A0, A1, A2, A3, A4, A5, A6) => C =
    (a0, a1, a2, a3, a4, a5, a6) => g(f(a0, a1, a2, a3, a4, a5, a6))

  private def andThen[A0, A1, A2, A3, A4, A5, A6, A7, B, C](f: (A0, A1, A2, A3, A4, A5, A6, A7) => B)(g: B => C): (A0, A1, A2, A3, A4, A5, A6, A7) => C =
    (a0, a1, a2, a3, a4, a5, a6, a7) => g(f(a0, a1, a2, a3, a4, a5, a6, a7))

  private def andThen[A0, A1, A2, A3, A4, A5, A6, A7, A8, B, C](f: (A0, A1, A2, A3, A4, A5, A6, A7, A8) => B)(g: B => C): (A0, A1, A2, A3, A4, A5, A6, A7, A8) => C =
    (a0, a1, a2, a3, a4, a5, a6, a7, a8) => g(f(a0, a1, a2, a3, a4, a5, a6, a7, a8))

  private def andThen[A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, B, C](f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9) => B)(g: B => C): (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9) => C =
    (a0, a1, a2, a3, a4, a5, a6, a7, a8, a9) => g(f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9))

  private def andThen[A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, B, C](f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10) => B)(g: B => C): (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10) => C =
    (a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10) => g(f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10))

  private def andThen[A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, B, C](f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11) => B)(g: B => C): (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11) => C =
    (a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11) => g(f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11))

  private def andThen[A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, B, C](f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12) => B)(g: B => C): (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12) => C =
    (a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12) => g(f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12))

  private def andThen[A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, B, C](f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13) => B)(g: B => C): (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13) => C =
    (a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13) => g(f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13))

  private def andThen[A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, B, C](f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14) => B)(g: B => C): (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14) => C =
    (a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14) => g(f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14))

  private def andThen[A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, B, C](f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15) => B)(g: B => C): (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15) => C =
    (a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15) => g(f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15))

  private def andThen[A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, B, C](f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16) => B)(g: B => C): (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16) => C =
    (a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16) => g(f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16))

  private def andThen[A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, B, C](f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17) => B)(g: B => C): (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17) => C =
    (a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17) => g(f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17))

  private def andThen[A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, B, C](f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18) => B)(g: B => C): (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18) => C =
    (a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18) => g(f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18))

  private def andThen[A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, B, C](f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19) => B)(g: B => C): (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19) => C =
    (a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19) => g(f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19))

  private def andThen[A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, B, C](f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20) => B)(g: B => C): (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20) => C =
    (a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19, a20) => g(f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19, a20))

  private def andThen[A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21, B, C](f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21) => B)(g: B => C): (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21) => C =
    (a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19, a20, a21) => g(f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19, a20, a21))
}
