import akka.actor._

class SillyActor extends Actor {

  override def preStart() {
    for (i <- 1 to 5) {
      println("I'm acting!")
      Thread.sleep(1000)
    }
  }

  def receive = { case _ => }
}

object ActorApp extends App {
  val system = ActorSystem("silly")

  val silly = system.actorOf(Props[SillyActor], "sillyactor")

  system.shutdown()
}
