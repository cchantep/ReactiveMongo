package reactivemongo

import scala.concurrent.{ Await, Future }

import akka.actor.ActorRef
import akka.testkit.TestActorRef

import org.specs2.matcher.MatchResult
import org.specs2.concurrent.ExecutionEnv

import reactivemongo.api.{
  AsyncDriver,
  MongoConnection,
  MongoConnectionOptions,
  ReadPreference
}

import reactivemongo.core.nodeset.{ Authenticate, NodeSet, ProtocolMetadata }

import reactivemongo.core.actors.{
  PrimaryAvailable,
  PrimaryUnavailable,
  SetAvailable,
  SetUnavailable,
  StandardDBSystem
}
import reactivemongo.core.actors.Exceptions.PrimaryUnavailableException

final class NodeSetSpec(implicit val ee: ExecutionEnv)
  extends org.specs2.mutable.Specification
  with ConnectAllTest with UnresponsiveSecondaryTest {

  "Node set" title

  sequential // for ConnectAllSpec

  import reactivemongo.api.tests._
  import tests.Common

  @inline def failoverStrategy = Common.failoverStrategy
  @inline def timeout = Common.timeout
  lazy val md = Common.newAsyncDriver()
  lazy val actorSystem = md.system

  protected val nodes = Seq(
    "nodesetspec.node1:27017", "nodesetspec.node2:27017")

  section("unit")
  "Node set" should {
    "not be available" >> {
      "if the entire node set is not available" in {
        scala.concurrent.Await.result(withConAndSys(md) {
          (con, _) => isAvailable(con, timeout)
        }, timeout) must throwA[java.util.concurrent.TimeoutException]
      }

      "if the primary is not available if default preference" in {
        withCon() { (name, con, mon) =>
          mon ! new SetAvailable(ProtocolMetadata.Default, None, false)

          waitIsAvailable(con, failoverStrategy).map(_ => true).recover {
            case reason: PrimaryUnavailableException if (
              reason.getMessage.indexOf(name) != -1) => false
          } must beFalse.await(1, timeout)
        }
      }
    }

    "be available" >> {
      "with the primary if default preference" in {
        withCon() { (_, con, mon) =>
          def test = (for {
            _ <- {
              mon ! new SetAvailable(ProtocolMetadata.Default, None, false)
              mon ! new PrimaryAvailable(ProtocolMetadata.Default, None, false)

              waitIsAvailable(con, failoverStrategy)
            }

            after <- isAvailable(con, timeout)
          } yield after)

          test must beTrue.await(1, timeout)
        }
      }

      "without the primary if slave ok" >> {
        org.specs2.specification.core.Fragments.foreach[ReadPreference](
          Seq(ReadPreference.primaryPreferred, ReadPreference.secondary)) { readPref =>
            s"using $readPref" in {
              val opts = MongoConnectionOptions.
                default.copy(readPreference = readPref)

              withCon(opts) { (_, con, mon) =>
                def test = (for {
                  _ <- {
                    mon ! new SetAvailable(
                      ProtocolMetadata.Default, None, false)

                    waitIsAvailable(con, failoverStrategy)
                  }
                  after <- isAvailable(con, timeout)
                } yield after)

                test must beTrue.await(1, timeout)
              }
            }
          }
      }
    }

    "be unavailable" >> {
      "with the primary unavailable if default preference" in {
        withCon() { (_, con, mon) =>
          mon ! new SetAvailable(ProtocolMetadata.Default, None, false)
          mon ! new PrimaryAvailable(ProtocolMetadata.Default, None, false)

          def test = (for {
            _ <- waitIsAvailable(con, failoverStrategy)
            before <- isAvailable(con, timeout)
            _ = mon ! PrimaryUnavailable
            after <- waitIsAvailable(
              con, failoverStrategy).map(_ => true).recover {
              case _ => false
            }
          } yield before -> after)

          test must beEqualTo(true -> false).await(1, timeout)
        }
      }

      "without the primary if slave ok" in {
        val opts = MongoConnectionOptions.default.copy(
          readPreference = ReadPreference.primaryPreferred)

        withCon(opts) { (_, con, mon) =>
          mon ! new SetAvailable(ProtocolMetadata.Default, None, false)

          def test = (for {
            _ <- waitIsAvailable(con, failoverStrategy)
            before <- isAvailable(con, timeout)
            _ = mon ! SetUnavailable
            after <- waitIsAvailable(
              con, failoverStrategy).map(_ => true).recover {
              case _ => false
            }
          } yield before -> after)

          test must beEqualTo(true -> false).await(1, timeout)
        }
      }
    }

    connectAllSpec

    unresponsiveSecondarySpec

    "discover node2 and create signaling channels" in {
      def isPrim = reactivemongo.bson.BSONDocument(
        "ok" -> 1,
        "ismaster" -> true,
        "minWireVersion" -> 4,
        "maxWireVersion" -> 5,
        "me" -> "nodesetspec.node1:27017",
        "setName" -> "rs0",
        "setVersion" -> 0,
        "secondary" -> false,
        "hosts" -> nodes,
        "primary" -> "nodesetspec.node1:27017")

      var before = Vector.empty[String]

      withConAndSys(md, _nodes = nodes.headOption.toSeq) { (_, sys) =>
        before = sys.underlyingActor._nodeSet.nodes.map(_.name)

        import scala.concurrent.duration._
        import akka.pattern.ask
        import akka.util.Timeout

        implicit def timeout = Timeout(3.seconds)

        ask(sys, fakeResponse(
          doc = isPrim, reqID = isMasterReqId())).mapTo[NodeSet]

      } must beLike[NodeSet] {
        case ns =>
          val after = ns.nodes

          before.size must_=== 1 and {
            before.headOption must_=== nodes.headOption
          } and {
            // Node2 discovered and signaling channel created for
            after.size must_=== 2
          } and {
            after.map(_.name).sorted must_=== nodes.toVector
          } and {
            after.exists(_.connections.count(_.signaling) == 1) must beTrue
          }
      }.awaitFor(timeout)
    }

    "be closed" in {
      md.close(timeout) must not(throwA[Exception])
    }
  }

  "Connection listener" should {
    import external.reactivemongo.ConnectionListener

    "not find class StaticListenerBinder" in {
      Class.forName("reactivemongo.core.StaticListenerBinder").
        aka("class loader") must throwA[ClassNotFoundException]
    }

    "be resolved as the default one" in {
      ConnectionListener().map(_.getClass.getName) must beNone
    }
  }
  section("unit")

  // ---

  def withConAndSys[T](drv: AsyncDriver, options: MongoConnectionOptions = MongoConnectionOptions.default.copy(nbChannelsPerNode = 1), _nodes: Seq[String] = nodes)(f: (MongoConnection, TestActorRef[StandardDBSystem]) => Future[T]): Future[T] = {
    // See AsyncDriver#connect
    val supervisorName = s"withConAndSys-sup-${System identityHashCode ee}"
    val poolName = s"withConAndSys-con-${System identityHashCode f}"

    @inline implicit def sys = drv.system

    val auths = Seq(Authenticate(
      Common.commonDb, "test", Some("password")))
    lazy val mongosystem = TestActorRef[StandardDBSystem](
      standardDBSystem(supervisorName, poolName, _nodes, auths, options),
      poolName)

    def connection = addConnection(
      drv, poolName, _nodes, options, mongosystem).mapTo[MongoConnection]

    connection.flatMap { con =>
      f(con, mongosystem).flatMap { res =>
        con.askClose()(timeout).recover { case _ => {} }.map(_ => res)
      }
    }
  }

  private def withCon[T](opts: MongoConnectionOptions = MongoConnectionOptions.default)(test: (String, MongoConnection, ActorRef) => MatchResult[T]): org.specs2.execute.Result = {
    val name = s"withCon-${System identityHashCode opts}"
    val con = md.connect(
      nodes, options = opts.copy(credentials = Map(
      Common.commonDb -> MongoConnectionOptions.Credential(
        "test", Some("password")))), name = Some(name))

    val res = con.flatMap { c =>
      actorSystem.actorSelection(s"/user/Monitor-$name").
        resolveOne(timeout).map(test(name, c, _)).andThen {
          case _ => try {
            Await.result(c.askClose()(timeout), timeout); ()
          } catch {
            case cause: Exception => cause.printStackTrace()
          }
        }
    }

    res.await(0, Common.slowTimeout /* tolerate nested timeout */ )
  }
}
