package cats

import cats.effect._
import izumi.reflect.macrortti._
import java.util.{Map => JMap}

package object layer {
  type ULayer[+A] = Layer[Any, A]

  type Tag[A] = izumi.reflect.Tag[A]
  lazy val Tag = izumi.reflect.Tag

  sealed trait Unsafe extends Serializable

  private[layer] def taggedTagType[A](
      tagged: Tag[A]
  ): LightTypeTag = tagged.tag

  private[layer] def taggedGetServices[A](t: LightTypeTag): Set[LightTypeTag] =
    t.decompose

  private[layer] def taggedIsSubtype(
      left: LightTypeTag,
      right: LightTypeTag
  ): Boolean =
    taggedSubtypes.computeIfAbsent(
      (left, right),
      new java.util.function.Function[(LightTypeTag, LightTypeTag), Boolean] {
        override def apply(tags: (LightTypeTag, LightTypeTag)): Boolean =
          tags._1 <:< tags._2
      }
    )

  private val taggedSubtypes: JMap[(LightTypeTag, LightTypeTag), Boolean] =
    new java.util.concurrent.ConcurrentHashMap[
      (LightTypeTag, LightTypeTag),
      Boolean
    ]

  object Unsafe {
    private[layer] val unsafe: Unsafe = new Unsafe {}

    def unsafe[A](f: Unsafe => A): A =
      f(unsafe)
  }
}
