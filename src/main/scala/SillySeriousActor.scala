package SillySerious

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

class SeriousActor extends Actor {
  override def preStart() {
    for (i <- 1 to 5) {
      println("To be or not to be.")
      Thread.sleep(1000)
    }
  }

  def receive = { case _ => }
}

object ActorApp extends App {
  val system = ActorSystem("system")
  val silly = system.actorOf(Props[SillyActor], "sillyactor")
  val serious = system.actorOf(Props[SeriousActor], "siriousactor")
  system.shutdown()
}
