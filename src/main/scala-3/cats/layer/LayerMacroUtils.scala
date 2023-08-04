package cats.layer

import scala.quoted._
import scala.compiletime._

private[layer] object LayerMacroUtils {
  type LayerExpr = Expr[Layer[_, _]]

  def renderExpr[A](expr: Expr[A])(using Quotes): String = {
    import quotes.reflect._
    scala.util
      .Try(expr.asTerm.pos.sourceCode)
      .toOption
      .flatten
      .getOrElse(expr.show)
  }

  def constructLayer[R0: Type, R: Type](using ctx: Quotes)(
      layers: Seq[Expr[Layer[_, _]]],
      provideMethod: ProvideMethod
  ): Expr[Layer[R0, R]] = {
    import ctx.reflect._

    val targetTypes = getRequirements[R]
    val remainderTypes = getRequirements[R0]

    val layerToDebug: PartialFunction[LayerExpr, Layer.Debug] =
      ((_: LayerExpr) match {
        case '{ Layer.Debug.tree } => Some(Layer.Debug.Tree)
        case _                     => None
      }).unlift

    val builder = LayerBuilder[TypeRepr, LayerExpr](
      target0 = targetTypes,
      remainder = remainderTypes,
      providedLayers0 = layers.toList,
      layerToDebug = layerToDebug,
      typeEquals = _ <:< _,
      sideEffectType = TypeRepr.of[Unit],
      foldTree = buildFinalTree,
      method = provideMethod,
      exprToNode = getNode,
      typeToNode = tpe =>
        Node(
          Nil,
          List(tpe),
          tpe.asType match { case '[t] => '{ Layer.environment[t] } }
        ),
      showExpr = expr =>
        scala.util
          .Try(expr.asTerm.pos.sourceCode)
          .toOption
          .flatten
          .getOrElse(expr.show),
      showType = _.show,
      reportWarn = report.warning(_),
      reportError = report.errorAndAbort(_)
    )

    builder.build.asInstanceOf[Expr[Layer[R0, R]]]
  }

  def buildFinalTree(
      tree: LayerTree[LayerExpr]
  )(using ctx: Quotes): LayerExpr = {
    import ctx.reflect._

    val empty: LayerExpr = '{ Layer.succeed(()) }

    def composeH(lhs: LayerExpr, rhs: LayerExpr): LayerExpr =
      lhs match {
        case '{ $lhs: Layer[i, o] } =>
          rhs match {
            case '{ $rhs: Layer[i2, o2] } =>
              '{ $lhs.++($rhs) }
          }
      }

    def composeV(lhs: LayerExpr, rhs: LayerExpr): LayerExpr =
      lhs match {
        case '{ $lhs: Layer[i, o] } =>
          rhs match {
            case '{ $rhs: Layer[`o`, o2] } =>
              '{ $lhs to $rhs }
          }
      }

    val layerExprs: List[LayerExpr] = tree.toList

    ValDef
      .let(Symbol.spliceOwner, layerExprs.map(_.asTerm)) { idents =>
        val exprMap = layerExprs.zip(idents).toMap

        tree
          .fold[LayerExpr](
            empty,
            exprMap(_).asExpr.asInstanceOf[LayerExpr],
            composeH,
            composeV
          )
          .asTerm

      }
      .asExpr
      .asInstanceOf[LayerExpr]

  }

  def getNode(
      layer: LayerExpr
  )(using ctx: Quotes): Node[ctx.reflect.TypeRepr, LayerExpr] = {
    import quotes.reflect._
    layer match {
      case '{ $layer: Layer[in, out] } =>
        val inputs = getRequirements[in]
        val outputs = getRequirements[out]
        Node(inputs, outputs, layer)
    }
  }

  def getRequirements[T: Type](using
      ctx: Quotes
  ): List[ctx.reflect.TypeRepr] = {
    import ctx.reflect._

    def loop(tpe: TypeRepr): List[TypeRepr] =
      tpe.dealias.simplified match {
        case AndType(lhs, rhs) =>
          loop(lhs) ++ loop(rhs)

        case AppliedType(_, TypeBounds(_, _) :: _) =>
          List.empty

        case other if other =:= TypeRepr.of[Any] =>
          List.empty

        case other if other.dealias.simplified != other =>
          loop(other)

        case other =>
          List(other.dealias)
      }

    loop(TypeRepr.of[T])
  }
}
