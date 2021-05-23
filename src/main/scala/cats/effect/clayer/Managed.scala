package cats.effect.clayer

import cats.effect._

object Managed {

  abstract class ReleaseMap[F[_]] {

    /**
     * An opaque identifier for a finalizer stored in the map.
     */
    type Key

    /**
     * Adds a finalizer to the finalizers associated with this scope. If the
     * finalizers associated with this scope have already been run this
     * finalizer will be run immediately.
     *
     * The finalizer returned from this method will remove the original finalizer
     * from the map and run it.
     */
    def add(finalizer: Finalizer[F]): F[Finalizer[F]]

    /**
     * Adds a finalizer to the finalizers associated with this scope. If the
     * scope is still open, a [[Key]] will be returned. This is an opaque identifier
     * that can be used to activate this finalizer and remove it from the map.
     * from the map. If the scope has been closed, the finalizer will be executed
     * immediately (with the [[Exit]] value with which the scope has ended) and
     * no Key will be returned.
     */
    def addIfOpen(finalizer: Finalizer[F]): F[Option[Key]]

    /**
     * Retrieves the finalizer associated with this key.
     */
    def get(key: Key): F[F[Finalizer[F]]]

    /**
     * Runs the specified finalizer and removes it from the finalizers
     * associated with this scope.
     */
    def release(key: Key, exit: Resource.ExitCase): F[Unit]

    /**
     * Runs the finalizers associated with this scope using the specified
     * execution strategy. After this action finishes, any finalizers added
     * to this scope will be run immediately.
     */
    def releaseAll(exit: Resource.ExitCase): F[Unit]

    /**
     * Removes the finalizer associated with this key and returns it.
     */
    def remove(key: Key): F[Option[Finalizer[F]]]

    /**
     * Replaces the finalizer associated with this key and returns it.
     * If the finalizers associated with this scope have already been run this
     * finalizer will be run immediately.
     */
    def replace(key: Key, finalizer: Finalizer[F]): F[Option[Finalizer[F]]]
  }
}
