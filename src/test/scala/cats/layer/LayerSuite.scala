package cats.layer

import cats.effect._
import cats.syntax.all._
import munit._

import Helpers._

object Helpers {
  def makeRes(name: String): Resource[IO, Unit] = {
    Resource.eval(Ref.of[IO, Boolean](false)).flatMap { released =>
      Resource.make(IO.println(s"init ${name}")) { _ =>
        released.modify {
          case false => (true, IO.println(s"destroy ${name}"))
          case true =>
            (true, IO.raiseError(new Exception(s"${name} already destroyed")))
        }.flatten
      }
    }
  }
}

trait DB
object DB {
  val live: Layer[Any, DB] = Layer.resource {
    makeRes("db").as(new DB {})
  }
}

trait HttpCli
object HttpCli {
  val live: Layer[Any, HttpCli] = Layer.resource {
    makeRes("httpClient").as(new HttpCli {})
  }
}

trait AClient
object AClient {
  val live: Layer[HttpCli, AClient] = Layer.resource { (a: HttpCli) =>
    makeRes("AClient").as(new AClient {})
  }
}

trait Service1
object Service1 {
  val live: Layer[DB & HttpCli, Service1] = Layer.fromFunction {
    (db: DB, http: HttpCli) =>
      new Service1 {}
  }
}
trait Service2
object Service2 {
  val live: Layer[DB & AClient, Service2] = Layer.fromFunction {
    (db: DB, aclient: AClient) =>
      new Service2 {}
  }
}

trait App
object App {
  val live: Layer[Service1 & Service2, App] = Layer.resource {
    (s1: Service1, s2: Service2) =>
      makeRes("app").as(new App {})
  }

}

class LayerSuite extends munit.FunSuite {
  test("Munally construction") {
    val appLayer: Layer[Any, App] =
      (DB.live ++ (HttpCli.live >+> AClient.live)) >>> (Service1.live ++ Service2.live) >>> App.live
    appLayer.build(ZEnv(())).use { app =>
      IO.println(s"Run app ${app}")
    }
  }
  test("Auto make layer") {
    val appLayer = Layer.make[App](
      DB.live,
      HttpCli.live,
      AClient.live,
      Service1.live,
      Service2.live,
      App.live
    )
    appLayer.build(ZEnv(())).use { app =>
      IO.println(s"Run app ${app}")
    }
  }
}
