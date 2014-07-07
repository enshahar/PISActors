package Dinning3

import akka.actor._
import scala.util.Random

class Philosopher(index:Int, leftChopStick:ActorPath, rightChopStick:ActorPath) extends Actor {
  override def preStart() {
    println(s"Philosopher ${index}[${self.path}] entered. Given left(${leftChopStick}) and right(${rightChopStick}).")
  }

  val STARVING_THRES = 5
  val EATING_THRES = 3
  val THINKING_THRES = 5

  var eatingTime = 0
  var waitingTime = 0
  var thinkingTime = 0
  
  def incWaiting() {
    waitingTime += 1
    if(waitingTime > STARVING_THRES)
      println(s"Philosopher ${index} waiting for ${waitingTime} second. Starving!!!")  
    else
      println(s"Philosopher ${index} waiting for ${waitingTime} second")  
  }
  
  def enterLeftWaiting() {
    context.become(waitingLeft)
    println(s"Philosopher ${index} waiting left chopstick")
    context.actorSelection(leftChopStick) ! "leftRequest"
  }

  def enterThinkingOrGetLeft() {
    thinkingTime = util.Random.nextInt(THINKING_THRES)
    if(thinkingTime == 0)
      enterLeftWaiting()
    else
      context.become(thinking)
  }

  def receive = { 
    case "start" => enterThinkingOrGetLeft()
  }
  
  def thinking: Receive = {
    case "tick" =>
      thinkingTime -= 1
      if(thinkingTime == 0)
        enterLeftWaiting()
      else
        println(s"Philosopher ${index} thinking")
  }
  
  def waitingLeft: Receive = {
    case "leftGot" =>
      println(s"Philosopher ${index} got left chopstick.")
      context.become(waitingRight)
    
    case "tick" =>
      println(s"Philosopher ${index} thinking. (wait left chopstick)")
      incWaiting()
      context.actorSelection(leftChopStick) ! "leftRequest"
  }
  
  def waitingRight: Receive = {
    case "rightGot" =>
      waitingTime = 0
      eatingTime = 0
      context.become(eating)
      println(s"Philosopher ${index} got all chopsticks. start eating")
      
    case "tick" =>
        println(s"Philosopher ${index} thinking. (wait right chopstick)")
        incWaiting()
        context.actorSelection(rightChopStick) ! "rightRequest"
  }
  
  def eating: Receive = {
    case "tick" =>
        eatingTime += 1
        println(s"Philosopher ${index} eating. For ${eatingTime} seconds.")
        if(eatingTime >= EATING_THRES) {
          context.become(releasingRight)
          println(s"Philosopher ${index} releasing right chopstick")
          context.actorSelection(rightChopStick) ! "rightRelease"
        }
  }
  
  def releasingRight: Receive = {
    case "rightReleased" =>
      context.become(releasingLeft)
      println(s"Philosopher ${index} releasing left chopstick")
      context.actorSelection(leftChopStick) ! "leftRelease"
  }
  
  def releasingLeft: Receive = {
    case "leftReleased" => enterThinkingOrGetLeft()
  }
}

class ChopStick(val index:Int) extends Actor {
  override def preStart() {  println(s"Chopstick ${index} on the table") }

  // 기본 receive(미사용 상태)  
  def receive = { 
    case "leftRequest" => 
      context.become(occupiedReceive, false)
      sender ! "leftGot"
      
    case "rightRequest" => 
      context.become(occupiedReceive, false)
      sender ! "rightGot"
  }
  
  // 사용중인 경우 receive
  def occupiedReceive:Receive = {
    case "leftRelease" => 
      context.unbecome
      sender ! "leftReleased"
      
    case "rightRelease" => 
      context.unbecome
      sender ! "rightReleased"
  }
}

class DinningRoom(val noOfMember: Int) extends Actor {
  assert(noOfMember >= 2)
  
  val chopSticks = for(i <- 1 to noOfMember) yield(context.actorOf(Props(new ChopStick(i))))
  
  val members = for(i <- 1 to noOfMember) yield(context.actorOf(Props(new Philosopher(i,
        chopSticks(i-1).path,
        if(i==noOfMember) chopSticks(0).path else chopSticks(i).path
  ))))

  val router = akka.routing.Router(akka.routing.BroadcastRoutingLogic(),   
                                  (members++chopSticks).map(akka.routing.ActorRefRoutee(_)))
  
  override def preStart() {
    println(s"Room ${self.path} created")
  }

  def receive = { 
    case "start" => { 
      println("START!")
      router.route("start", sender())
    }
    case "tick" => router.route("tick", sender())
    case "end" => println("FINISH!")
  }
}

object ActorApp extends App {
  assert(args.length == 2)
  val nMember = args(0).toInt
  val nSecond = args(1).toInt
  assert(nMember >= 3)
  assert(nSecond > 5)
  val system = ActorSystem("system")
  val room = system.actorOf(Props(new DinningRoom(nMember)), "dinningRoom")
  room ! "start"
  for(i <- 1 to nSecond) {
    Thread.sleep(1000)
    println(s"##### ${i} #####")
    room ! "tick"
  }
  room ! "end"
  system.shutdown()
}
