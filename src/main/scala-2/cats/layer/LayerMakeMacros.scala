package cats.layer

import ansi.AnsiStringOps
import scala.reflect.macros.blackbox

final class LayerMakeMacros(val c: blackbox.Context) extends LayerMacroUtils {
  import c.universe._

  def makeImpl[
      R0: c.WeakTypeTag,
      R: c.WeakTypeTag
  ](layer: c.Expr[Layer[_, _]]*)(
      dummyKRemainder: c.Expr[DummyK[R0]],
      dummyK: c.Expr[DummyK[R]]
  ): c.Expr[Layer[R0, R]] = {
    val _ = (dummyK, dummyKRemainder)
    assertEnvIsNotNothing[R]()
    constructLayer[R0, R](layer, ProvideMethod.Provide)
  }

  def makeSomeImpl[
      R0: c.WeakTypeTag,
      R: c.WeakTypeTag
  ](layer: c.Expr[Layer[_, _]]*)(
      dummyKRemainder: c.Expr[DummyK[R0]],
      dummyK: c.Expr[DummyK[R]]
  ): c.Expr[Layer[R0, R]] = {
    val _ = (dummyK, dummyKRemainder)
    assertEnvIsNotNothing[R]()
    constructLayer[R0, R](layer, ProvideMethod.ProvideSome)
  }

  /** Ensures the macro has been annotated with the intended result type. The
    * macro will not behave correctly otherwise.
    */
  private def assertEnvIsNotNothing[R: c.WeakTypeTag](): Unit = {
    val outType = weakTypeOf[R]
    val nothingType = weakTypeOf[Nothing]
    if (outType == nothingType) {
      val errorMessage =
        s"""
${"  Layer Wiring Error  ".red.bold.inverted}

You must provide a type to ${"make".cyan.bold} (e.g. ${"Layer.make".cyan.bold}${"[A with B]".cyan.bold.underlined}${"(A.live, B.live)".cyan.bold})

"""
      c.abort(c.enclosingPosition, errorMessage)
    }
  }

}
