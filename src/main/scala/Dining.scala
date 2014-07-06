package Dinning

import akka.actor._
import scala.util.Random

class Philosopher(index:Int, leftChopStick:ActorPath, rightChopStick:ActorPath) extends Actor {
  override def preStart() {
	println(s"Philosopher ${index}[${self.path}] entered. Given left(${leftChopStick}) and right(${rightChopStick}).")
  }

  object Status extends Enumeration {
	type Status = Value
	val WaitLeft, WaitRight, Eating, ReleaseRight, ReleaseLeft = Value
  }
  import Status._
  
  val STARVING_THRES = 5
  val EATING_THRES = 3

  var status = WaitLeft
  var eatingTime = 0
  var waitingTime = 0
  
  def incWaiting() {
	waitingTime += 1
	if(waitingTime > STARVING_THRES)
		println(s"Philosopher ${index} waiting for ${waitingTime} second. Starving!!!")	
	else
		println(s"Philosopher ${index} waiting for ${waitingTime} second")	
  }
  
  def receive = { 
    case "start" => 
		println(s"Philosopher ${index} waiting left chopstick)")
		context.actorSelection(leftChopStick) ! "leftRequest"
	case "tick" => {
		if(status == WaitLeft) {
			println(s"Philosopher ${index} thinking. (wait left chopstick)")
			incWaiting()
			context.actorSelection(leftChopStick) ! "leftRequest"
		} else if(status == WaitRight) {
			println(s"Philosopher ${index} thinking. (wait right chopstick)")
			incWaiting()
			context.actorSelection(rightChopStick) ! "rightRequest"
		} else if(status == Eating) {
			eatingTime += 1
			println(s"Philosopher ${index} eating. For ${eatingTime} seconds.")
			if(eatingTime >= EATING_THRES) {
				eatingTime = 0
				status = ReleaseRight
				println(s"Philosopher ${index} releasing right chopstick")
				context.actorSelection(rightChopStick) ! "rightRelease"
			}
		} else { 
			// do nothing while releasing chopsticks
		}		
	}
	case "leftGot" => {
		status = WaitRight
		println(s"Philosopher ${index} got left chopstick.")
	}
	case "rightGot" => {
		status = Eating
		waitingTime = 0
		println(s"Philosopher ${index} got all chopsticks. start eating")
	}
	case "rightReleased" => {
		status = ReleaseLeft
		println(s"Philosopher ${index} releasing left chopstick")
		context.actorSelection(leftChopStick) ! "leftRelease"
	}
	case "leftReleased" => {
		status = WaitLeft
		println(s"Philosopher ${index} released all chopsticks")
	}
  }
}

class ChopStick(val index:Int) extends Actor {
  override def preStart() {	println(s"Chopstick ${index} on the table") }

  var occupied = false
  
  def receive = { 
	case "leftRequest" => 
		if(!occupied) { 
			sender ! "leftGot"
			occupied = true
		}
	case "rightRequest" => 
		if(!occupied) { 
			sender ! "rightGot"
			occupied = true
		}
	case "leftRelease" => 
		assert(occupied)
		sender ! "leftReleased"
		occupied = false
	case "rightRelease" => 
		assert(occupied)
		sender ! "rightReleased"
		occupied = false
  }
}

class DinningRoom(val noOfMember: Int) extends Actor {
  assert(noOfMember >= 2)
  
  val chopSticks = for(i <- 1 to noOfMember) yield(context.actorOf(Props(new ChopStick(i))))
  
  val members = for(i <- 1 to noOfMember) yield(context.actorOf(Props(new Philosopher(i,
        chopSticks(i-1).path,
	if(i==noOfMember) chopSticks(0).path else chopSticks(i).path
  ))))

  override def preStart() {
	println(s"Room ${self.path} created")
  }

  def receive = { 
	case "start" => { 
		println("START!")
		Random.shuffle(members).foreach(x => x ! "start")
	}
	case "tick" => Random.shuffle(members).foreach(x => x ! "tick")
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
