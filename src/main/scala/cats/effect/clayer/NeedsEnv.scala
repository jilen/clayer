package cats.effect.clayer

import scala.annotation.implicitAmbiguous

/**
 * A value of type `NeedsEnv[R]` provides implicit evidence that an effect with
 * environment type `R` needs an environment, that is, that `R` is not equal to
 * `Any`.
 */
sealed abstract class NeedsEnv[+R] extends Serializable

object NeedsEnv extends NeedsEnv[Nothing] {

  implicit def needsEnv[R]: NeedsEnv[R] = NeedsEnv

  // Provide multiple ambiguous values so an implicit NeedsEnv[Any] cannot be found.
  @implicitAmbiguous(
    "This operation assumes that your effect requires an environment. " +
      "However, your effect has Any for the environment type, which means it " +
      "has no requirement, so there is no need to provide the environment."
  )
  implicit val needsEnvAmbiguous1: NeedsEnv[Any] = NeedsEnv
  implicit val needsEnvAmbiguous2: NeedsEnv[Any] = NeedsEnv
}
