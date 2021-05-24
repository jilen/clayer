package cats.effect.clayer

/**
 * A `MemoMap` memoizes dependencies.
 */
private[clayer] abstract class MemoMap[F[_]] { self =>

  /**
   * Checks the memo map to see if a dependency exists. If it is, immediately
   * returns it. Otherwise, obtains the dependency, stores it in the memo map,
   * and adds a finalizer to the outer `Managed`.
   */
  def getOrElseMemoize[ A, B](layer: CLayer[A,  B]): ZManaged[A,  B]
}

private[clayer] object MemoMap {

  /**
   * Constructs an empty memo map.
   */
  def make[F[_]]: F[MemoMap[F]] =
    RefM
      .make[Map[CLayer[F, Nothing, Any], (IO[Any, Any], Managed.Finalizer)]](Map.empty)
      .map { ref =>
        new MemoMap { self =>
          final def getOrElseMemoize[ A, B](layer: CLayer[A,  B]): ZManaged[A,  B] =
            ZManaged {
              ref.modify { map =>
                map.get(layer) match {
                  case Some((acquire, release)) =>
                    val cached =
                      ZIO.accessM[(A, ReleaseMap)] {
                        case (_, releaseMap) =>
                          acquire
                            .asInstanceOf[IO[ B]]
                            .onExit {
                              case Exit.Success(_) => releaseMap.add(release)
                              case Exit.Failure(_) => UIO.unit
                            }
                            .map((release, _))
                      }

                    UIO.succeed((cached, map))

                  case None =>
                    for {
                      observers    <- Ref.make(0)
                      promise      <- Promise.make[B]
                      finalizerRef <- Ref.make[ZManaged.Finalizer](ZManaged.Finalizer.noop)

                      resource = ZIO.uninterruptibleMask { restore =>
                        for {
                          env                  <- ZIO.environment[(A, ReleaseMap)]
                          (a, outerReleaseMap) = env
                          innerReleaseMap      <- ZManaged.ReleaseMap.make
                          tp <- restore(layer.scope.flatMap(_.apply(self)).zio.provide((a, innerReleaseMap))).run.flatMap {
                            case e @ Exit.Failure(cause) =>
                              promise.halt(cause) *> innerReleaseMap.releaseAll(e, ExecutionStrategy.Sequential) *> ZIO
                                .halt(cause)

                            case Exit.Success((_, b)) =>
                              for {
                                _ <- finalizerRef.set { (e: Exit[Any, Any]) =>
                                  ZIO.whenM(observers.modify(n => (n == 1, n - 1)))(
                                    innerReleaseMap.releaseAll(e, ExecutionStrategy.Sequential)
                                  )
                                }
                                _              <- observers.update(_ + 1)
                                outerFinalizer <- outerReleaseMap.add(e => finalizerRef.get.flatMap(_.apply(e)))
                                _              <- promise.succeed(b)
                              } yield (outerFinalizer, b)
                          }
                        } yield tp
                      }

                      memoized = (
                        promise.await.onExit {
                          case Exit.Failure(_) => UIO.unit
                          case Exit.Success(_) => observers.update(_ + 1)
                        },
                        (exit: Exit[Any, Any]) => finalizerRef.get.flatMap(_(exit))
                      )
                    } yield (resource, if (layer.isFresh) map else map + (layer -> memoized))

                }
              }.flatten
            }
        }
      }
}
