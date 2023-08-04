package cats.layer

import ansi.AnsiStringOps
import scala.quoted._
import scala.compiletime._
import java.nio.charset.StandardCharsets
import java.util.Base64

import LayerMacroUtils._

object LayerMacros {
  def constructLayer[R0: Type, R: Type](
      layers: Expr[Seq[Layer[_, _]]]
  )(using Quotes): Expr[Layer[R0, R]] =
    layers match {
      case Varargs(layers) =>
        LayerMacroUtils.constructLayer[R0, R](layers, ProvideMethod.Provide)
    }

}
