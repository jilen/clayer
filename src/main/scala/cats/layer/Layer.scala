package cats.layer

import cats.Parallel
import cats.syntax.all._
import cats.effect._
import cats.effect.std.MapRef
import cats.effect.syntax.all._
import macros.{DummyK, LayerMakeMacros}

/*
 * Copyright 2017-2022 John A. De Goes and the ZIO Contributors
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

/*
 * Highly inspired by zio.ZLayer
 */
sealed trait Layer[-RIn, +ROut] { self =>

  final def zipWithPar[RIn2, ROut1 >: ROut, ROut2, ROut3](
      that: => Layer[RIn2, ROut2]
  )(
      f: (ZEnv[ROut], ZEnv[ROut2]) => ZEnv[ROut3]
  ): Layer[RIn & RIn2, ROut3] =
    Layer.suspend(Layer.ZipWithPar(self, that, f))

  final def ++[RIn2, ROut1 >: ROut, ROut2](
      that: => Layer[RIn2, ROut2]
  )(implicit
      tag: Tag[ROut2]
  ): Layer[RIn & RIn2, ROut1 & ROut2] =
    self.zipWithPar(that)(_.union[ROut2](_))

  def build: ZEnv[RIn] => Resource[IO, ZEnv[ROut]] = { rIn =>
    Layer.MemoMap.make.flatMap { memoMap =>
      scope(memoMap)(rIn)
    }
  }

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
      case f: Layer.Fold[RIn, _, ROut] =>
        scopeFold(f)
      case Layer.Suspend(f) =>
        memoMap.getOrElseMemoize(f())
      case f: Layer.To[RIn, _, ROut] =>
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

  def apply[RIn, ROut](
      a: ZEnv[RIn] => Resource[IO, ZEnv[ROut]]
  ): Layer[RIn, ROut] = Layer.Apply(a)

  private sealed trait ToRes[F[_]] {
    def apply[A](fa: F[A]): Resource[IO, A]
  }

  final class FromFunctionPartialApplied[F1[_]](toRes: ToRes[F1]) {
    def apply[A: Tag](f: () => F1[A]): ULayer[A] =
      Layer(_ => toRes(f()).map(ZEnv[A](_)))

    def apply[A: Tag, B: Tag](f: (A) => F1[B]): Layer[A, B] = Layer {
      (env: ZEnv[A]) =>
        toRes(f(env.get[A])).map(ZEnv[B](_))
    }

    def apply[A: Tag, B: Tag, C: Tag](
        f: (A, B) => F1[C]
    ): Layer[A & B, C] = Layer { (env) =>
      toRes(f(env.get[A], env.get[B])).map(ZEnv[C](_))
    }

    def apply[A: Tag, B: Tag, C: Tag, D: Tag](
        f: (A, B, C) => F1[D]
    ): Layer[A & B & C, D] = Layer { (env) =>
      toRes(f(env.get[A], env.get[B], env.get[C])).map(ZEnv[D](_))
    }

    def apply[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag](
        f: (A, B, C, D) => F1[E]
    ): Layer[A & B & C & D, E] = Layer { (env) =>
      toRes(f(env.get[A], env.get[B], env.get[C], env.get[D])).map(ZEnv[E](_))
    }

    def apply[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, F: Tag](
        f: (A, B, C, D, E) => F1[F]
    ): Layer[A & B & C & D & E, F] = Layer { (env) =>
      toRes(f(env.get[A], env.get[B], env.get[C], env.get[D], env.get[E]))
        .map(ZEnv[F](_))
    }

    def apply[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, F: Tag, G: Tag](
        f: (A, B, C, D, E, F) => F1[G]
    ): Layer[A & B & C & D & E & F, G] = Layer { (env) =>
      toRes(
        f(
          env.get[A],
          env.get[B],
          env.get[C],
          env.get[D],
          env.get[E],
          env.get[F]
        )
      )
        .map(ZEnv[G](_))
    }

    def apply[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, F: Tag, G: Tag, H: Tag](
        f: (A, B, C, D, E, F, G) => F1[H]
    ): Layer[A & B & C & D & E & F & G, H] = Layer { (env) =>
      toRes(
        f(
          env.get[A],
          env.get[B],
          env.get[C],
          env.get[D],
          env.get[E],
          env.get[F],
          env.get[G]
        )
      ).map(ZEnv[H](_))
    }

    def apply[
        A: Tag,
        B: Tag,
        C: Tag,
        D: Tag,
        E: Tag,
        F: Tag,
        G: Tag,
        H: Tag,
        I: Tag
    ](
        f: (A, B, C, D, E, F, G, H) => F1[I]
    ): Layer[A & B & C & D & E & F & G & H, I] = Layer { (env) =>
      toRes(
        f(
          env.get[A],
          env.get[B],
          env.get[C],
          env.get[D],
          env.get[E],
          env.get[F],
          env.get[G],
          env.get[H]
        )
      ).map(ZEnv[I](_))
    }

    def apply[
        A: Tag,
        B: Tag,
        C: Tag,
        D: Tag,
        E: Tag,
        F: Tag,
        G: Tag,
        H: Tag,
        I: Tag,
        J: Tag
    ](
        f: (A, B, C, D, E, F, G, H, I) => F1[J]
    ): Layer[A & B & C & D & E & F & G & H & I, J] = Layer { (env) =>
      toRes(
        f(
          env.get[A],
          env.get[B],
          env.get[C],
          env.get[D],
          env.get[E],
          env.get[F],
          env.get[G],
          env.get[H],
          env.get[I]
        )
      ).map(ZEnv[J](_))
    }

  }

  final val fromFunction: FromFunctionPartialApplied[cats.Id] =
    new FromFunctionPartialApplied(new ToRes[cats.Id] {
      def apply[A](fa: A): Resource[IO, A] = Resource.pure(fa)
    })

  final val eval: FromFunctionPartialApplied[IO] =
    new FromFunctionPartialApplied(new ToRes[IO] {
      def apply[A](fa: IO[A]): Resource[IO, A] = Resource.eval(fa)
    })

  type ResourceF[A] = Resource[IO, A]

  final val resource: FromFunctionPartialApplied[ResourceF] =
    new FromFunctionPartialApplied(new ToRes[ResourceF] {
      def apply[A](fa: Resource[IO, A]): Resource[IO, A] = fa
    })

  def fromEnv[A](ra: => ZEnv[A]): ULayer[A] = apply(_ => Resource.pure(ra))

  def succeed[A: Tag](a: => A): ULayer[A] = apply(_ => Resource.pure(ZEnv(a)))

  def resource[A: Tag](a: => Resource[IO, A]): ULayer[A] =
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
      type V = Resource[IO, ZEnv[Any]]

      Resource
        .make(Ref.of[IO, List[IO[Unit]]](Nil)) { finalizers =>
          finalizers.get.flatMap { fs =>
            fs.parSequence_
          }
        }
        .evalMap { finalizers =>
          MapRef.ofConcurrentHashMap[IO, K, V]().map { mapRef =>
            new MemoMap { self =>
              final def getOrElseMemoize[A, B](
                  layer: Layer[A, B]
              ): ZEnv[A] => Resource[IO, ZEnv[B]] = { (in: ZEnv[A]) =>
                val ref = mapRef(layer)
                val getOrInitRes = ref.get.flatMap {
                  case Some(p) =>
                    IO.pure(p)
                  case None =>
                    layer
                      .scope(self)(in)
                      .memoize
                      .allocated
                      .flatMap { case (res, finalizer) =>
                        ref
                          .modify {
                            case None => Some(res) -> (res, true)
                            case Some(otherRes) =>
                              Some(otherRes) -> (otherRes, false)
                          }
                          .flatMap { case (res, success) =>
                            if (success) {
                              finalizers.update(finalizer :: _).as(res)
                            } else {
                              finalizer.as(res)
                            }
                          }
                      }
                      .uncancelable
                }
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
        that: => Layer[ROut & RIn2, ROut2]
    )(implicit tag: Tag[ROut]): Layer[RIn & RIn2, ROut2] =
      Layer.suspend(Layer.To(Layer.environment[RIn2] ++ self, that))

    /** Feeds the output services of this layer into the input of the specified
      * layer, resulting in a new layer & the inputs of this layer as well as
      * any leftover inputs, and the outputs of the specified layer.
      */
    def >>>[ROut2](that: => Layer[ROut, ROut2]): Layer[RIn, ROut2] =
      Layer.suspend(Layer.To(self, that))

    /** Feeds the output services of this layer into the input of the specified
      * layer, resulting in a new layer & the inputs of this layer, and the
      * outputs of both layers.
      */
    def >+>[RIn2, ROut2](
        that: => Layer[ROut & RIn2, ROut2]
    )(implicit
        tagged: Tag[ROut],
        tagged2: Tag[ROut2],
        trace: Trace
    ): Layer[RIn & RIn2, ROut & ROut2] =
      self ++ self.>>>[RIn2, ROut2](that)

    /** Feeds the output services of this layer into the input of the specified
      * layer, resulting in a new layer & the inputs of this layer, and the
      * outputs of both layers.
      */
    def >+>[RIn2 >: ROut, ROut1 >: ROut, ROut2](
        that: => Layer[RIn2, ROut2]
    )(implicit
        tagged: Tag[ROut2]
    ): Layer[RIn, ROut1 & ROut2] =
      Layer.ZipWith[RIn, ROut1, ROut2, ROut1 & ROut2](
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

final class MakePartiallyApplied[R](val dummy: Boolean = true) extends AnyVal {
  import scala.language.experimental.macros
  def apply(
      layer: Layer[_, _]*
  )(implicit
      dummyKRemainder: DummyK[Any],
      dummyK: DummyK[R]
  ): Layer[Any, R] =
    macro LayerMakeMacros.makeImpl[Any, R]
}

final class MakeSomePartiallyApplied[R0, R](
    val dummy: Boolean = true
) extends AnyVal {
  import scala.language.experimental.macros
  def apply(
      layer: Layer[_, _]*
  )(implicit dummyKRemainder: DummyK[R0], dummyK: DummyK[R]): Layer[R0, R] =
    macro LayerMakeMacros.makeSomeImpl[R0, R]
}
