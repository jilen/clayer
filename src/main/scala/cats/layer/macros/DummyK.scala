package cats.layer.macros

/** DummyK is used to pull `WeakTypeTag` information into a Macro when there is
  * otherwise no value to extract it from. See: [[ZLayerMakeMacros.makeImpl]]
  */
private[layer] final case class DummyK[A]()

private[layer] object DummyK {
  private val singleton: DummyK[Any] = DummyK()
  implicit def dummyK[A]: DummyK[A] = singleton.asInstanceOf[DummyK[A]]
}
