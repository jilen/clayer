package cats.layer

import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.collection.mutable.ListBuffer
import scala.collection.{immutable, mutable}
import TerminalRendering.LayerWiringError
import ansi._

/** LayerBuilder houses the core logic for compile-time layer construction. It
  * is parameterized by `Type` and `Expr` such that it can be shared across
  * Scala 2 and 3, which have incompatible macro libraries.
  *
  * @param target
  *   A list of types indicating the intended output of the final layer. This is
  *   generally determined by the `R` of the effect that [[ZIO.provide]] is
  *   called on.
  * @param remainder
  *   A list of types indicating the input of the final layer. This would be the
  *   parameter of [[ZIO.provideSome]]
  * @param providedLayers0
  *   A list of layers ASTs that have been provided by the user.
  * @param layerToDebug
  *   A method which allows LayerBuilder to filter/extract the special
  *   ZLayer.Debug layers from the provided layers.
  * @param typeEquals
  *   A method for comparing types: Used in the construction of the final layer
  * @param foldTree
  *   A method for folding a tree of layers into the final layer.
  * @param method
  *   The sort of method that is being called: `provide`, `provideSome`, or
  *   `provideCustom`. This is used to provide improved compilation warnings.
  * @param exprToNode
  *   A method for converting an Expr into a Node for use in the graph
  *   traversal.
  * @param typeToNode
  *   A method for converting a leftover type into a Node to be used in the
  *   graph traversal.
  * @param showExpr
  * @param showType
  * @param reportWarn
  * @param reportError
  */
final case class LayerBuilder[Type, Expr](
    target0: List[Type],
    remainder: List[Type],
    providedLayers0: List[Expr],
    layerToDebug: PartialFunction[Expr, Layer.Debug],
    sideEffectType: Type,
    typeEquals: (Type, Type) => Boolean,
    foldTree: LayerTree[Expr] => Expr,
    method: ProvideMethod,
    exprToNode: Expr => Node[Type, Expr],
    typeToNode: Type => Node[Type, Expr],
    showExpr: Expr => String,
    showType: Type => String,
    reportWarn: String => Unit,
    reportError: String => Nothing
) {

  lazy val target =
    if (method.isProvideSomeShared)
      target0.filterNot(t1 => remainder.exists(t2 => typeEquals(t1, t2)))
    else target0

  private lazy val remainderNodes: List[Node[Type, Expr]] =
    remainder.map(typeToNode).distinct

  private val (providedLayers, maybeDebug)
      : (List[Expr], Option[Layer.Debug]) = {
    val maybeDebug = providedLayers0.collectFirst(layerToDebug)
    val layers = providedLayers0.filterNot(layerToDebug.isDefinedAt)
    (layers.distinct, maybeDebug)
  }

  private val (sideEffectNodes, providedLayerNodes)
      : (List[Node[Type, Expr]], List[Node[Type, Expr]]) =
    providedLayers
      .map(exprToNode)
      .partition(_.outputs.exists(typeEquals(_, sideEffectType)))

  def build: Expr = {
    assertNoAmbiguity()

    /** Build the layer tree. This represents the structure of a successfully
      * constructed Layer that will build the target types. This, of course, may
      * fail with one or more GraphErrors.
      */
    val layerTreeEither: Either[::[GraphError[Type, Expr]], LayerTree[Expr]] = {
      val nodes: List[Node[Type, Expr]] =
        providedLayerNodes ++ remainderNodes ++ sideEffectNodes
      val graph = Graph(nodes, typeEquals)

      for {
        original <- graph.buildComplete(target)
        sideEffects <- graph.buildNodes(sideEffectNodes)
      } yield sideEffects ++ original
    }

    layerTreeEither match {
      case Left(buildErrors) =>
        reportBuildErrors(buildErrors)

      case Right(tree) =>
        warnUnused(tree)
        maybeDebug.foreach(debugLayer(_, tree))
        foldTree(tree)
    }
  }

  /** Checks to see if any type is provided by multiple layers. If so, this will
    * report a compilation error about ambiguous layers. Otherwise, our
    * algorithm would arbitrarily choose one of the layers to satisfy the type,
    * possibly confusing the user and breaking stuff.
    */
  private def assertNoAmbiguity(): Unit = {
    val typesToExprs: Map[String, List[String]] =
      groupMap(providedLayerNodes.flatMap { node =>
        node.outputs.map(output => showType(output) -> showExpr(node.value))
      })(_._1)(_._2)

    val duplicates: List[(String, List[String])] =
      typesToExprs.toList.filter { case (_, list) => list.size > 1 }

    if (duplicates.nonEmpty) {
      val message = "\n" + TerminalRendering.ambiguousLayersError(duplicates)
      reportError(message)
    }
  }

  /** Given a LayerTree, this will warn about all provided layers that are not
    * used. This will also warn about any specified remainders that aren't
    * actually required, in the case of provideSome/provideCustom.
    */
  private def warnUnused(tree: LayerTree[Expr]): Unit = {
    val usedLayers =
      tree.map(showExpr).toSet

    val unusedUserLayers =
      providedLayerNodes
        .map(n => showExpr(n.value))
        .toSet -- usedLayers -- remainderNodes.map(n => showExpr(n.value))

    if (unusedUserLayers.nonEmpty) {
      val message =
        "\n" + TerminalRendering.unusedLayersError(unusedUserLayers.toList)
      reportWarn(message)
    }

    val unusedRemainderLayers =
      remainderNodes.filterNot(node => usedLayers(showExpr(node.value)))

    method match {
      case ProvideMethod.Provide => ()
      case ProvideMethod.ProvideSome | ProvideMethod.ProvideSomeShared =>
        if (!method.isProvideSomeShared && unusedRemainderLayers.nonEmpty) {
          val message = "\n" + TerminalRendering.unusedProvideSomeLayersError(
            unusedRemainderLayers.map(node => showType(node.outputs.head))
          )
          reportWarn(message)
        }

        if (remainder.isEmpty) {
          val message = "\n" + TerminalRendering.provideSomeNothingEnvError
          reportWarn(message)
        }
      case ProvideMethod.ProvideCustom =>
        if (unusedRemainderLayers.length == remainderNodes.length) {
          val message = "\n" + TerminalRendering.superfluousProvideCustomError
          reportWarn(message)
        }
    }
  }

  private def reportBuildErrors(errors: ::[GraphError[Type, Expr]]): Nothing = {
    val allErrors = sortErrors(errors).map(renderError).toList

    val topLevelErrors = allErrors.collect {
      case top: LayerWiringError.MissingTopLevel =>
        top.layer
    }.distinct

    val transitive = allErrors
      .collect { case LayerWiringError.MissingTransitive(layer, deps) =>
        layer -> deps
      }
      .groupBy(_._1)
      .map { case (key, value) =>
        key -> value.flatMap(_._2).distinct
      }

    val circularErrors = allErrors.collect {
      case LayerWiringError.Circular(layer, dep) =>
        layer -> dep
    }

    if (circularErrors.nonEmpty)
      reportError(TerminalRendering.circularityError(circularErrors))
    else
      reportError(
        TerminalRendering.missingLayersError(
          topLevelErrors,
          transitive,
          method.isProvideSome
        )
      )
  }

  /** Return only the first level of circular dependencies, as these will be the
    * most relevant.
    */
  private def sortErrors(
      errors: ::[GraphError[Type, Expr]]
  ): Vector[GraphError[Type, Expr]] = {
    val (circularDependencyErrors, otherErrors) = {
      errors.toVector.partitionMap {
        case circularDependency: GraphError.CircularDependency[Type, Expr] =>
          Left(circularDependency)
        case other => Right(other)
      }
    }
    val sorted = circularDependencyErrors.sortBy(_.depth)
    val initialCircularErrors =
      sorted.takeWhile(_.depth == sorted.headOption.map(_.depth).getOrElse(0))

    val (transitiveDepErrors, remainingErrors) = otherErrors.partitionMap {
      case es: GraphError.MissingTransitiveDependencies[Type, Expr] => Left(es)
      case other => Right(other)
    }

    val groupedTransitiveErrors =
      transitiveDepErrors.groupBy(_.node).map { case (node, errors) =>
        val layer = errors.flatMap(_.dependency)
        GraphError.MissingTransitiveDependencies(node, layer)
      }

    initialCircularErrors ++ groupedTransitiveErrors ++ remainingErrors
  }

  private def renderError(error: GraphError[Type, Expr]): LayerWiringError =
    error match {
      case GraphError.MissingTransitiveDependencies(node, dependencies) =>
        LayerWiringError.MissingTransitive(
          showExpr(node.value),
          dependencies.map(showType).toList
        )

      case GraphError.MissingTopLevelDependency(dependency) =>
        LayerWiringError.MissingTopLevel(showType(dependency))

      case GraphError.CircularDependency(node, dependency, _) =>
        LayerWiringError.Circular(
          showExpr(node.value),
          showExpr(dependency.value)
        )
    }

  /** Emits a compilation notice with a visual representation of the constructed
    * Layer.
    */
  private def debugLayer(debug: Layer.Debug, tree: LayerTree[Expr]): Unit = {
    val renderedTree: String =
      tree
        .map(expr => RenderedGraph(showExpr(expr)))
        .fold[RenderedGraph](
          RenderedGraph.Row(List.empty),
          identity,
          _ ++ _,
          _ >>> _
        )
        .render

    val title = "  Layer Wiring Graph  ".yellow.bold.inverted
    val builder = new StringBuilder
    builder ++= "\n" + title + "\n\n" + renderedTree + "\n\n"
    reportWarn(builder.result())
  }

  // Backwards compatibility for 2.11/2.12
  private def groupMap[A, K, B](
      as: List[A]
  )(key: A => K)(f: A => B): Map[K, List[B]] = {
    val m = mutable.Map.empty[K, mutable.Builder[B, List[B]]]
    for (elem <- as) {
      val k = key(elem)
      val builder = m.getOrElseUpdate(k, new ListBuffer[B])
      builder += f(elem)
    }
    var result = immutable.Map.empty[K, List[B]]
    m.foreach { case (k, v) =>
      result = result + ((k, v.result()))
    }
    result
  }

}

sealed trait ProvideMethod extends Product with Serializable {
  def isProvideSomeShared: Boolean = this == ProvideMethod.ProvideSomeShared
  def isProvideSome: Boolean =
    this == ProvideMethod.ProvideSome || this == ProvideMethod.ProvideSomeShared

}

object ProvideMethod {
  case object Provide extends ProvideMethod
  case object ProvideSome extends ProvideMethod
  case object ProvideSomeShared extends ProvideMethod
  case object ProvideCustom extends ProvideMethod
}
