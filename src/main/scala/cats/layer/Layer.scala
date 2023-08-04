package cats.layer

import cats.Parallel
import cats.syntax.all._
import cats.effect._
import cats.effect.std.MapRef
import cats.effect.syntax.all._

/** Highly inspired by zio.ZLayer
  */
sealed trait Layer[-RIn, +ROut] { self =>

  final def zipWithPar[RIn2, ROut1 >: ROut, ROut2, ROut3](
      that: => Layer[RIn2, ROut2]
  )(
      f: (ZEnv[ROut], ZEnv[ROut2]) => ZEnv[ROut3]
  ): Layer[RIn with RIn2, ROut3] =
    Layer.suspend(Layer.ZipWithPar(self, that, f))

  final def ++[RIn2, ROut1 >: ROut, ROut2](
      that: => Layer[RIn2, ROut2]
  )(implicit
      tag: Tag[ROut2]
  ): Layer[RIn with RIn2, ROut1 with ROut2] =
    self.zipWithPar(that)(_.union[ROut2](_))

  def build: ZEnv[RIn] => Resource[IO, ZEnv[ROut]] = { rIn =>
    Layer.MemoMap.make.flatMap { memoMap =>
      scope(memoMap)(rIn)
    }
  }

  final def widen[ROut2 >: ROut]: Layer[RIn, ROut2] = {
    self
  }

  final def flatMap[RIn1 <: RIn, ROut2](
      f: ZEnv[ROut] => Layer[RIn1, ROut2]
  ): Layer[RIn1, ROut2] =
    foldLayer(Layer.fail(_), f)

  final def foldLayer[RIn1 <: RIn, ROut2](
      failure: Throwable => Layer[RIn1, ROut2],
      success: ZEnv[ROut] => Layer[RIn1, ROut2]
  ): Layer[RIn1, ROut2] =
    Layer.Fold(self, failure, success)

  private final def scope
      : Layer.MemoMap => (ZEnv[RIn] => Resource[IO, ZEnv[ROut]]) = { memoMap =>
    def scopeFold[RIn, ROut, ROut2](module: Layer.Fold[RIn, ROut, ROut2]) = {
      (in: ZEnv[RIn]) =>
        memoMap
          .getOrElseMemoize(module.self)(in)
          .redeemWith(
            e => memoMap.getOrElseMemoize(module.failure(e))(in),
            rout => memoMap.getOrElseMemoize(module.success(rout))(in)
          )
    }

    def scopeTo[RIn, ROut, ROut2](m: Layer.To[RIn, ROut, ROut2]) = {
      (in: ZEnv[RIn]) =>
        memoMap.getOrElseMemoize(m.self)(in).flatMap { rout =>
          memoMap.getOrElseMemoize(m.that)(rout)
        }
    }
    this match {
      case Layer.Apply(f) =>
        f
      case f: Layer.Fold[RIn, _, ROut] @unchecked =>
        scopeFold(f)
      case Layer.Suspend(f) =>
        memoMap.getOrElseMemoize(f())
      case f: Layer.To[RIn, _, ROut] @unchecked =>
        scopeTo(f)
      case Layer.ZipWith(self, that, folder) => { (in) =>
        memoMap
          .getOrElseMemoize(self)(in)
          .map2(memoMap.getOrElseMemoize(that)(in))(folder)
      }
      case Layer.ZipWithPar(self, that, folder) => { (in) =>
        Parallel.parMap2(
          memoMap.getOrElseMemoize(self)(in),
          memoMap.getOrElseMemoize(that)(in)
        )(folder)
      }
    }
  }
}

object Layer extends LayerMaker {

  private[layer] trait RefCount[+A] {
    def acquireRef: Resource[IO, A]
  }

  private[layer] object RefCount {

    private class Impl[A](
        refCount: Ref[IO, Int],
        releaseSignal: Deferred[IO, Unit],
        memoized: Resource[IO, A]
    ) extends RefCount[A] {
      def acquireRef =
        Resource.make(refCount.update(_ + 1)) { _ =>
          refCount
            .updateAndGet(_ - 1)
            .flatMap(r => releaseSignal.complete(()).whenA(r == 0))
        } >> memoized
    }

    def apply[A](ra: Resource[IO, A]): IO[(RefCount[A], IO[Unit])] = {
      for {
        refCount <- Ref.of[IO, Int](0)
        releaseSignal <- Deferred[IO, Unit]
        resPair <- ra.memoize.allocated
        (memoized, finalizer) = resPair
      } yield new Impl(
        refCount,
        releaseSignal,
        memoized
      ) -> (releaseSignal.get >> finalizer).uncancelable
    }
  }

  sealed trait Debug

  object Debug {
    private[layer] type Tree = Tree.type

    private[layer] case object Tree extends Debug

    /** Including this layer in a call to a compile-time Layer constructor, such
      * as [[ZIO.provide]] or [[Layer.make]], will display a tree visualization
      * of the constructed layer graph.
      *
      * {{{
      *   val layer =
      *     Layer.make[OldLady](
      *       OldLady.live,
      *       Spider.live,
      *       Fly.live,
      *       Bear.live,
      *       Layer.Debug.tree
      *     )
      *
      * // Including `Layer.Debug.tree` will generate the following compilation error:
      * //
      * // ◉ OldLady.live
      * // ├─◑ Spider.live
      * // │ ╰─◑ Fly.live
      * // ╰─◑ Bear.live
      * //   ╰─◑ Fly.live
      *
      * }}}
      */
    val tree: ULayer[Debug] =
      Layer.succeed[Debug](Debug.Tree)(Tag[Debug])
  }

  private def apply[RIn, ROut](
      a: ZEnv[RIn] => Resource[IO, ZEnv[ROut]]
  ): Layer[RIn, ROut] = Layer.Apply(a)

  trait FunctionConstructor[In] {
    type Out
    def apply(in: In): Out
  }

  object FunctionConstructor {
    type Aux[In, Out0] = FunctionConstructor[In] {
      type Out = Out0
    }

    implicit def function0Constructor[A: Tag]
        : FunctionConstructor.Aux[() => Resource[IO, A], Layer[Any, A]] =
      new FunctionConstructor[() => Resource[IO, A]] {
        type Out = Layer[Any, A]
        def apply(f: () => Resource[IO, A]): Layer[Any, A] =
          Layer(_ => f().map(ZEnv(_)))
      }
    implicit def function2Constructor[A: Tag, B: Tag]
        : FunctionConstructor.Aux[(A) => Resource[IO, B], Layer[A, B]] =
      new FunctionConstructor[(A) => Resource[IO, B]] {
        type Out = Layer[A, B]
        def apply(f: (A) => Resource[IO, B]): Layer[A, B] = Layer { (env) =>
          f(env.get[A]).map(ZEnv[B](_))
        }
      }

    implicit def function3Constructor[A: Tag, B: Tag, C: Tag]
        : FunctionConstructor.Aux[(A, B) => Resource[IO, C], Layer[A & B, C]] =
      new FunctionConstructor[(A, B) => Resource[IO, C]] {
        type Out = Layer[A & B, C]
        def apply(f: (A, B) => Resource[IO, C]): Layer[A & B, C] = Layer {
          (env) =>
            f(env.get[A], env.get[B]).map(ZEnv[C](_))
        }
      }
  }

  final def function[In](
      in: In
  )(implicit fc: FunctionConstructor[In]): fc.Out = {
    fc(in)
  }

  def succeedEnv[A](ra: => ZEnv[A]): ULayer[A] = apply(_ => Resource.pure(ra))

  def succeed[A: Tag](a: => A): ULayer[A] = apply(_ => Resource.pure(ZEnv(a)))

  def eval[A: Tag](a: IO[A]): Layer[Any, A] = resource(a.toResource)

  def fail[A: Tag](e: Throwable): Layer[Any, A] =
    Layer.resource(IO.raiseError(e).toResource)

  def make[A: Tag](acquire: IO[A])(release: A => IO[Unit]): Layer[Any, A] = {
    resource(Resource.make(acquire)(release))
  }

  def resource[A: Tag](a: => Resource[IO, A]): Layer[Any, A] =
    apply(_ => a.map(ZEnv(_)))

  def suspend[RIn, ROut](layer: => Layer[RIn, ROut]): Layer[RIn, ROut] = {
    lazy val self = layer
    Suspend(() => self)
  }

  def environment[A]: Layer[A, A] = apply(Resource.pure)

  trait MemoMap {
    def getOrElseMemoize[RIn, ROut](
        m: Layer[RIn, ROut]
    ): ZEnv[RIn] => Resource[IO, ZEnv[ROut]]
  }

  object MemoMap {

    /** Constructs an empty memo map.
      */
    def make: Resource[IO, MemoMap] = {
      type K = Layer[Nothing, Any]
      type V = Deferred[IO, RefCount[ZEnv[Any]]]

      Resource
        .make(Ref.of[IO, List[IO[Unit]]](Nil)) { finalizers =>
          finalizers.get.flatMap(_.parSequence_)
        }
        .evalMap { finalizers =>
          MapRef.ofConcurrentHashMap[IO, K, V]().map { mapRef =>
            new MemoMap { self =>
              final def getOrElseMemoize[A, B](
                  layer: Layer[A, B]
              ): ZEnv[A] => Resource[IO, ZEnv[B]] = { (in: ZEnv[A]) =>
                val ref = mapRef(layer)
                val getOrInitRes = ref.get
                  .flatMap {
                    case Some(p) =>
                      IO.pure(p)
                    case None =>
                      val res = layer.scope(self)(in)
                      Deferred[IO, RefCount[ZEnv[Any]]].flatMap { holder =>
                        val completeRef =
                          RefCount(res).flatMap { case (refCount, finalizer) =>
                            finalizers.update(_ :+ finalizer) >> holder
                              .complete(refCount)
                          }
                        ref.flatModify {
                          case None =>
                            (Some(holder), completeRef.as(holder))
                          case Some(h) =>
                            (Some(h), IO(h))
                        }
                      }
                  }
                  .flatMap(_.get.map(_.acquireRef))
                  .uncancelable
                Resource.suspend(getOrInitRes).map(_.asInstanceOf[ZEnv[B]])
              }

            }
          }
        }
    }
  }

  implicit final class LayerProvideSomeOps[RIn, ROut](
      val self: Layer[RIn, ROut]
  ) extends AnyVal {

    /** Feeds the output services of this layer into the input of the specified
      * layer, resulting in a new layer with the inputs of this layer as well as
      * any leftover inputs, and the outputs of the specified layer.
      */
    def >>>[RIn2, ROut2](
        that: => Layer[ROut with RIn2, ROut2]
    )(implicit tag: Tag[ROut]): Layer[RIn with RIn2, ROut2] =
      Layer.suspend(Layer.To(Layer.environment[RIn2] ++ self, that))

    /** A named alias for `>>>`.
      */
    def to[RIn2, ROut2](
        that: => Layer[ROut with RIn2, ROut2]
    )(implicit tag: Tag[ROut]): Layer[RIn with RIn2, ROut2] =
      >>>[RIn2, ROut2](that)

    /** Feeds the output services of this layer into the input of the specified
      * layer, resulting in a new layer with the inputs of this layer as well as
      * any leftover inputs, and the outputs of the specified layer.
      */
    def >>>[ROut2](that: => Layer[ROut, ROut2]): Layer[RIn, ROut2] =
      Layer.suspend(Layer.To(self, that))

    /** Feeds the output services of this layer into the input of the specified
      * layer, resulting in a new layer with the inputs of this layer, and the
      * outputs of both layers.
      */
    def >+>[RIn2, ROut2](
        that: => Layer[ROut with RIn2, ROut2]
    )(implicit
        tagged: Tag[ROut],
        tagged2: Tag[ROut2],
        trace: Trace
    ): Layer[RIn with RIn2, ROut with ROut2] =
      self ++ self.>>>[RIn2, ROut2](that)

    /** Feeds the output services of this layer into the input of the specified
      * layer, resulting in a new layer with the inputs of this layer, and the
      * outputs of both layers.
      */
    def >+>[RIn2 >: ROut, ROut1 >: ROut, ROut2](
        that: => Layer[RIn2, ROut2]
    )(implicit
        tagged: Tag[ROut2]
    ): Layer[RIn, ROut1 with ROut2] =
      Layer.ZipWith[RIn, ROut1, ROut2, ROut1 with ROut2](
        self,
        self >>> that,
        _.union[ROut2](_)
      )
  }

  private final case class Apply[-RIn, ROut](
      self: ZEnv[RIn] => Resource[IO, ZEnv[ROut]]
  ) extends Layer[RIn, ROut]

  private final case class Fold[RIn, ROut, ROut2](
      self: Layer[RIn, ROut],
      failure: Throwable => Layer[RIn, ROut2],
      success: ZEnv[ROut] => Layer[RIn, ROut2]
  ) extends Layer[RIn, ROut2]

  private final case class Suspend[-RIn, +ROut](
      self: () => Layer[RIn, ROut]
  ) extends Layer[RIn, ROut]

  private final case class To[RIn, ROut, ROut1](
      self: Layer[RIn, ROut],
      that: Layer[ROut, ROut1]
  ) extends Layer[RIn, ROut1]

  private final case class ZipWith[-RIn, ROut, ROut2, ROut3](
      self: Layer[RIn, ROut],
      that: Layer[RIn, ROut2],
      f: (ZEnv[ROut], ZEnv[ROut2]) => ZEnv[ROut3]
  ) extends Layer[RIn, ROut3]

  private final case class ZipWithPar[-RIn, ROut, ROut2, ROut3](
      self: Layer[RIn, ROut],
      that: Layer[RIn, ROut2],
      f: (ZEnv[ROut], ZEnv[ROut2]) => ZEnv[ROut3]
  ) extends Layer[RIn, ROut3]

}

trait LayerMaker {

  /** Automatically assembles a layer for the provided type.
    *
    * {{{
    * ZLayer.make[Car](carLayer, wheelsLayer, engineLayer)
    * }}}
    */
  def make[R]: MakePartiallyApplied[R] =
    new MakePartiallyApplied[R]

  /** Automatically constructs a layer for the provided type `R`, leaving a
    * remainder `R0`.
    *
    * {{{
    * val carLayer: ZLayer[Engine with Wheels, Nothing, Car] = ???
    * val wheelsLayer: ZLayer[Any, Nothing, Wheels] = ???
    *
    * val layer = ZLayer.makeSome[Engine, Car](carLayer, wheelsLayer)
    * }}}
    */
  def makeSome[R0, R]: MakeSomePartiallyApplied[R0, R] =
    new MakeSomePartiallyApplied[R0, R]
}
