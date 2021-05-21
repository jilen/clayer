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
import cats.data._
import cats.effect._
import cats.syntax.all._

 class CLayer[F[_] : MonadCancel[*[_], Throwable], -RIn, +ROut](val reader: ReaderT[F, RIn, Resource[F, ROut]]) { self =>


  type Layer[RIn, ROut] = CLayer[F, RIn, ROut]


  final def +!+[ RIn2, ROut1 >: ROut, ROut2](
    that: Layer[RIn2, ROut2]
  )(implicit ev: Has.UnionAll[ROut1, ROut2]): Layer[RIn with RIn2, ROut1 with ROut2] =
    self.zipWithPar(that)(ev.unionAll)

  /**
   * Combines this layer with the specified layer, producing a new layer that
   * has the inputs of both layers, and the outputs of both layers.
   */
  final def ++[RIn2, ROut1 >: ROut, ROut2](
    that: Layer[RIn2, ROut2]
  )(implicit ev: Has.Union[ROut1, ROut2], tag: Tag[ROut2]): Layer[RIn with RIn2, ROut1 with ROut2] =
    self.zipWithPar(that)(ev.union)

  /**
   * A symbolic alias for `zipPar`.
   */
  final def <&>[RIn2, ROut2](that: Layer[RIn2, ROut2]): Layer[RIn with RIn2, (ROut, ROut2)] =
    zipWithPar(that)((_, _))

  /**
   * A symbolic alias for `orElse`.
   */
  def <+>[RIn1 <: RIn, ROut1 >: ROut](
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
  )(implicit ev: Has.Union[ROut1, ROut2], tagged: Tag[ROut2]): Layer[RIn, ROut1 with ROut2] = {
    CLayer.zipWith(self, self >>> that)(ev.union)
  }


  /**
   * Feeds the output services of this layer into the input of the specified
   * layer, resulting in a new layer with the inputs of this layer, and the
   * outputs of the specified layer.
   */
  final def >>>[ROut2](that: Layer[ROut, ROut2]): Layer[RIn, ROut2] =
    fold(CLayer.fromFunctionManyM { case (_, cause) => Raise[F].raiseError(cause)  }, that)

  /**
   * A named alias for `++`.
   */
  final def and[RIn2, ROut1 >: ROut, ROut2](
    that: Layer[RIn2, ROut2]
  )(implicit ev: Has.Union[ROut1, ROut2], tagged: Tag[ROut2]): Layer[RIn with RIn2, ROut1 with ROut2] =
    self.++[RIn2, ROut1, ROut2](that)

  /**
   * A named alias for `>+>`.
   */
  final def andTo[RIn2 >: ROut, ROut1 >: ROut, ROut2](
    that: Layer[RIn2, ROut2]
  )(implicit ev: Has.Union[ROut1, ROut2], tagged: Tag[ROut2]): Layer[RIn, ROut1 with ROut2] =
    self.>+>[RIn2, ROut1, ROut2](that)

  /**
   * Builds a layer into a managed value.
   */
  final def build: RIn => Resource[F, ROut] = { rIn =>
    for {
      memoMap <- Resource.eval(CLayer.MemoMap.make)
      run     <- self.scope
      value   <- run(memoMap)(rIn)
    } yield value
  }

  /**
   * Recovers from all errors.
   */
  final def catchAll[RIn1 <: RIn, ROut1 >: ROut](
    handler: Layer[(RIn1, Throwable), ROut1]
  ): Layer[RIn1, ROut1] = {
    fold(handler, CLayer.identity)
  }

   private final def scope:  Resource[F, CLayer.MemoMap =>  RIn => Resource[F, ROut]] = ???


  /**
   * Feeds the error or output services of this layer into the input of either
   * the specified `failure` or `success` layers, resulting in a new layer with
   * the inputs of this layer, and the error or outputs of the specified layer.
   */
  final def fold[RIn1 <: RIn, ROut2](
    failure: Layer[(RIn1, Throwable), ROut2],
    success: Layer[ROut, ROut2]
  ): Layer[RIn1, ROut2] = ???


  /**
   * Returns the hash code of this layer.
   */
  override final lazy val hashCode: Int =
    super.hashCode

  /**
   * Builds this layer and uses it until it is interrupted. This is useful when
   * your entire application is a layer, such as an HTTP server.
   */
  final def launch(implicit ev: Any <:< RIn): F[Unit] = build(ev).allocated.void


  /**
   * Returns a new layer whose output is mapped by the specified function.
   */
  final def map[ROut1](f: ROut => ROut1): Layer[RIn, ROut1] =
    self >>> CLayer.fromFunctionMany(f)


  /**
   * Executes this layer and returns its output, if it succeeds, but otherwise
   * executes the specified layer.
   */
  final def orElse[RIn1 <: RIn, ROut1 >: ROut](
    that: Layer[RIn1, ROut1]
  ): Layer[RIn1, ROut1] =
    catchAll(CLayer.first >>> that)


  /**
   * Performs the specified effect if this layer succeeds.
   */
  final def tap[RIn1 <: RIn](f: ROut => RIn1 => F[Any]): Layer[RIn1, ROut] =
    Layer.identity <&> self >>> Layer.fromFunctionManyM { case (in, out) => f(out).provide(in) *> ZIO.succeed(out) }


  /**
   * A named alias for `>>>`.
   */
  final def to[ROut2](that: Layer[ROut, ROut2]): Layer[RIn, ROut2] =
    self >>> that


  /**
   * Updates one of the services output by this layer.
   */
  final def update[A: Tag](f: A => A)(implicit ev1: Has.IsHas[ROut], ev2: ROut <:< Has[A]): Layer[RIn, ROut] =
    self >>> Layer.fromFunctionMany(ev1.update[ROut, A](_, f))

  /**
   * Combines this layer with the specified layer, producing a new layer that
   * has the inputs of both layers, and the outputs of both layers combined
   * into a tuple.
   */
  final def zipPar[RIn2, ROut2](that: Layer[RIn2, E1, ROut2]): Layer[RIn with RIn2, (ROut, ROut2)] =
    zipWithPar(that)((_, _))

  /**
   * Combines this layer with the specified layer, producing a new layer that
   * has the inputs of both layers, and the outputs of both layers combined
   * using the specified function.
   */
  final def zipWithPar[RIn2, ROut1 >: ROut, ROut2, ROut3](
    that: Layer[RIn2, ROut2]
  )(f: (ROut, ROut2) => ROut3): Layer[RIn with RIn2, ROut3] =
    Layer.ZipWithPar(self, that, f)

}

object CLayer {
  def zipWith[F[_], RIn, ROut1, ROut2, ROut3](self: CLayer[F, RIn, ROut1], that: CLayer[F, RIn, ROut2])(f: (ROut1, ROut2) => ROut3): CLayer[F, RIn, ROut3] = {
    val newReader = self.reader.flatMap { out =>
      that.reader(out).map(out2 => ev.union(out, out2))
    }
    CLayer(newReader)
  }

  def fromFunctionMany[F[_]: Applicative, A, B](f: A => B): CLayer[F, A, B] = {
    fromFunctionManyM(f.map(Applicative[F].pure))
  }

  def fromFunctionManyM[F[_], A, B](f: A => F[B]): CLayer[F, A, B] = {
    fromFunctionManyManaged(f.map(b => Resource.eval(b)))
  }

  def fromFunctionManyManaged[F[_], A, B](f: A => Resource[F, B]): CLayer[F, A, B] =  {
    new CLayer(Kleisli(f))
  }

  trait MemoMap[F[_]] {
    def toManaged_ : Resource[F, MemoMap[F]] = ???
  }

  object MemoMap {
    def make[F[_]]: F[MemoMap[F]] = ???
  }

  def identity[F[_]: Applicative, A]: CLayer[F, A, A] = requires[F, A]

  def requires[F[_]: Applicative, A]: CLayer[F, A, A] = {
    fromFunctionManyM(Applicative[F].pure)
  }



}
