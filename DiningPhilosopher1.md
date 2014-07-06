---
layout: post
title: [스칼라] akka 버전 철학자의 만찬(Dining Philosopher) - 1. 마구마구만들기
tags:
- scala
- 스칼라
- 액터
- akka
- 철학자의 만찬
- Dining Philosopher
---
[스칼라] akka 버전 철학자의 만찬(Dining Philosopher) - 1

뭐니뭐니해도 가장 유명한 동시성 문제는 역시 철학자의 만찬일 것이다.

이 문제는 다음과 같이 정의할 수 있다.

* n명의 철학자가 있다.
* n개의 젓가락(한쌍이 아니라 한짝)이 각 철학자 사이에 있다. 원래는 포크지만..
* n개의 라멘(내가 라멘 팬이라서)그릇이 철학자 앞에 놓여있다. 원래는 스파게티이다.
* 각 철학자 사이에는 의사소통이 불가능하다.
* 각 철학자는 다음과 같은 순서로 라면을 먹는다.
    1. 왼쪽 젓가락을 집어든다. 왼쪽 젓가락을 누군가 사용중이라면, 그 젓가락이 사용가능해 질 때 까지 계속 생각한다(철학자니까!).
    2. 왼쪽 젓가락을 가졌다면, 오른쪽 젓가락을 집어든다. 오른쪽 젓가락을 누가 사용중이라면, 그 젓가락이 사용 가능해질
때까지 계속 생각한다.
    3.  왼쪽과 오른쪽 젓가락을 모두 가졌다면 정해진 시간동안 라멘을 먹는다.
    4.  라멘을 다 먹었으면 오른쪽 젓가락을 내려놓는다.
    5.  오른쪽 젓가락을 내려놓았으면, 왼쪽 젓가락도 내려놓는다.
    6.  다시 1부터 반복한다.

이 문제가 유명해진 이유는 다음과 같이 동시성 프로그래밍에서 발생할 수 있는  여러가지 문제를 잘 보여주는 쉬운 예제이기 때문이다.

* 젓가락은 공유자원이다. 이 문제는 공유자원에 대한 경합(contention)을 보여주는 좋은 예이다.
* 잘 생각해 보면 모든 철학자들이 젓가락을 기다리느라 아무도 라멘을 못 먹는 사태가 발생한다(모든 철학자가 우연히 왼쪽
젓가락을 집어들면 모두 다 누군가 젓가락을 내려놓기를 기다려야 한다. 그런데, 모두다 대기상태이기 때문에 결코 젓가락을
내려놓을 수가 없다). 이를 교착상태(deadlock)이라 한다.
* 운이 없는 철학자라면 계속해서 라멘을 못 먹는 사태가 발생할 수 있다. 이런 경우를 아사(starvation)이라 한다.

이 문제를 akka로 구현해 보자. 일단 오늘은 그냥 말 그대로 구현에만 중점을 둘 것이다. 최적화하거나 이런저런 akka 기능을 활용하는 것은 다음 단계에서 진행할 것이다.

## 젓가락 액터

먼저 젓가락을 구현해보자. 여러가지 방법이 있겠지만, 젓가락 자신이 어느 철학자에게 속했는지를 표시하는 변수 하나를 사용하고, 메시지로는 젓가락 요청과 젓가락 내려놓기 메시지를 만든다. 다음 소스코드를 보라.

```
class ChopStick(val index:Int) extends Actor {
  override def preStart() {	println(s"Chopstick ${index} on the table") }

  var occupied = false  // 젓가락을 누군가 사용중인지 나타냄
  
  def receive = { 
	case "leftRequest" => // 철학자가 왼쪽에 있는 젓가락을 요청
		if(!occupied) { 
			sender ! "leftGot"
			occupied = true
		}
	case "rightRequest" => // 철학자가 오른쪽에 있는 젓가락을 요청
		if(!occupied) { 
			sender ! "rightGot"
			occupied = true
		}
	case "leftRelease" => // 철학자가 왼쪽 젓가락을 내려놓음
		assert(occupied)
		sender ! "leftReleased"
		occupied = false
	case "rightRelease" => // 철학자가 오른쪽 젓가락을 내려놓음
		assert(occupied)
		sender ! "rightReleased"
		occupied = false
  }
}
```

메시지가 중복이 있어 보이고, copy&paste한 코드라서 맘에 걸리긴 하지만 일단 넘어가자. 다음은 철학자를 만들어야 한다.

## 철학자 액터
철학자는 젓가락을 두개 받아야 한다. 젓가락을 두개 받으려면 여러가지 방법이 있겠지만, 생성자 인자로 만들었다. 이때 `ActorRef`를 사용하는 것이 정석(?)이겠지만, 그냥 `ActorPath`를 받게 만들어 보았다. 이렇게 만든 철학자 액터 시작부분은 다음과 같다.

```
class Philosopher(index:Int, leftChopStick:ActorPath, rightChopStick:ActorPath) extends Actor {
  override def preStart() {
	println(s"Philosopher ${index}[${self.path}] entered. Given left(${leftChopStick}) and right(${rightChopStick}).")
  }
```

철학자는 앞에서 이야기한 각 단계를 거쳐야 한다. 각 단계를 상태로 만들기 위해 변수를 추가하고, 상태를 표시하기 위해 열거형(enumeration)을 하나 만든다. 처음 시작 상태는 왼쪽 젓가락을 기다리는 상태이다.

```
  object Status extends Enumeration {
	type Status = Value
	val WaitLeft, WaitRight, Eating, ReleaseRight, ReleaseLeft = Value
  }
  import Status._
  
  var status = WaitLeft
```

원래는 별도 쓰레드에서 각 액터가 따로 움직여야 하지만, 여기서는 중앙에 클럭을 두고, 그 클럭이 보내는 틱에 따라 움직이게 했다. 다만, 젓가락을 집어들거나 내려 놓기 위한 요청을 젓가락에 보낸 다음, 젓가락에서 응답을 받는 것은 클럭과 관계 없이 수행한다. 그래서, 중앙 클럭에서 시작 메시지("start")와 틱 메시지("tick")를 받는 것으로 구현했다.

먼저 시작 메시지를 받으면, 젓가락을 집어들기 위해 왼쪽 젓가락에 요청을 보낸다.

```
  def receive = { 
    case "start" => // 시작 메시지. 중앙 클럭(또는 코디)에게서 받음
      println(s"Philosopher ${index} waiting left chopstick)")
      context.actorSelection(leftChopStick) ! "leftRequest"
```

틱을 받으면 현재 상태에 따라 적절히 다음 상태로 진행한다.

```
	case "tick" => { // 틱 메시지. 코디에게서 받음
		if(status == WaitLeft) { // 왼쪽 대기상태에 시간만 속절없이...
			println(s"Philosopher ${index} thinking. (wait left chopstick)")
			incWaiting() // 초 증가. 아사여부 체크
			context.actorSelection(leftChopStick) ! "leftRequest"
		} else if(status == WaitRight) { // 왼쯕을 집어들고, 오른쪽 젓가락을 기다리는 중
			println(s"Philosopher ${index} thinking. (wait right chopstick)")
			incWaiting()
			context.actorSelection(rightChopStick) ! "rightRequest"
		} else if(status == Eating) { // 라멘먹기
			eatingTime += 1
			println(s"Philosopher ${index} eating. For ${eatingTime} seconds.")
			// EATING_THRES초 동안 먹고나면 젓가락을 내려놓는다.
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
```

`incWaiting()`은 대기상태로 1초가 지날 때마다 카운터를 증가해 가면서 아사 여부를 판정하는 것이다. 아사판정 기준은 `STARVING_THRES`라는 변수에 넣어둔다.

```
  def incWaiting() {
	waitingTime += 1
	if(waitingTime > STARVING_THRES)
		println(s"Philosopher ${index} waiting for ${waitingTime} second. Starving!!!")	
	else
		println(s"Philosopher ${index} waiting for ${waitingTime} second")	
  }
```

젓가락을 집어드는데 성공하는 경우나 내려놓는데 성공(내려놓는데 실패해서는 안되겠지만)에는 그에 따라 다음 상태로 이동한다.
```
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
  }  // receive의 끝
} // 철학자 액터의 끝
```

이제, 철학자나 젓가락은 다 만들었다. 이제 클럭(또는 코디네이터) 액터를 만들어야 한다. 이 액터가 할 일은 다음과 같다.

1. 젓가락 액터들을 만든다
1. 철학자 액터들을 만들면서 젓가락의 경로를 철학자의 생성자에 인자로 넘긴다
1. 시간이 바뀌면 tick을 철학자들에게 브로드캐스팅한다.
1. 시작시 start메시지를 철학자들에게 브로드캐스팅한다.

위 1,2는 꼭 코디에서 할 필요가 없기는 하지만, 코디가 브로드캐스팅하려면 어차피 모든 액터에 대한 참조를 보관해야 하므로, 코디에서 만드는 편이 좋다. 코디 액터의 이름은 DinningRoom이라고 붙였다.

```
class DiningRoom(val noOfMember: Int) extends Actor {
  assert(noOfMember >= 2)
  
  val chopSticks = for(i <- 1 to noOfMember) yield(context.actorOf(Props(new ChopStick(i))))
  
  val members = for(i <- 1 to noOfMember) yield(context.actorOf(Props(new Philosopher(i, chopSticks(i-1).path,
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
```

`chopSticks`는 젓가락의 리스트이고, `members`는 철학자의 리스트이다. 철학자의 리스트를 만들 때, `new Philosopher(i,` 다음에 오는 if문은 오른쪽과 왼쪽 젓가락의 인덱스를 제대로 계산하기 위한 것이다. 4개의 젓가락과 4명의 철학자가 있다면 인덱스는 다음과 같이 만들어야 하기 때문이다.(맨 뒤의 C1은 맨 앞의 C1과 같다. []안은 chopSticks와 members에서의 인덱스이다)

- P1 : 왼쪽 [0], 오른쪽 [1]
- P2 : 왼쪽 [1], 오른쪽 [2]
- P3 : 왼쪽 [2], 오른쪽 [3]
- P4 : 왼쪽 [3], 오른쪽 [0]

`Random.shuffle(members)`는 호출 순서를 뒤섞기 위한 것이다. 실험해본 결과로는 뒤섞지 않고 그냥 `members.foreach`로 일정한 순서로 메시지를 보내면 결과가 고정적인 것 같아 보인다.

이제 세 클래스를 다 만들었다. 전체를 연결해 akka 시스템을 만들고 실행할 애플리케이션이 필요하다.

## 애플리케이션

인자를 두개 받게 만들었다. 첫번째는 전체 철학자 인원수이고(3이상), 두번째 인자는 몇초동안 실행할 것인가(반복회수, 5이상)이다. 오류체크는 그냥 assert로 했고, 문자열 정수 변환시 발생하는 오류도 특별히 잡지 않았다. 더 친절하게 해 줄 수도 있지만 그게 목적이 아니니까.

```
object ActorApp extends App {
  assert(args.length == 2)
  val nMember = args(0).toInt
  val nSecond = args(1).toInt
  assert(nMember>=3)
  assert(nSecond>5)
  val system = ActorSystem("system")
  val room = system.actorOf(Props(new DinningRoom(5)), "dinningRoom")
  room ! "start"
  for(i <- 1 to 10) {
	Thread.sleep(1000)
    println(s"##### ${i} #####")
	room ! "tick"
  }
  room ! "end"
  system.shutdown()
}
```

기대에 차서 실행해 보면...

```
> run 5 10

Multiple main classes detected, select one to run:

 [1] HelloWorld
 [2] ActorApp
 [3] SillySerious.ActorApp
 [4] Dinning.ActorApp

Enter number: 4

[info] Running Dinning.ActorApp 5 10
Chopstick 1 on the table
Chopstick 2 on the table
Chopstick 4 on the table
Chopstick 3 on the table
Chopstick 5 on the table
Room akka://system/user/dinningRoom created
START!
Philosopher 1[akka://system/user/dinningRoom/$f] entered. Given left(akka://syst
em/user/dinningRoom/$a) and right(akka://system/user/dinningRoom/$b).
Philosopher 2[akka://system/user/dinningRoom/$g] entered. Given left(akka://syst
em/user/dinningRoom/$b) and right(akka://system/user/dinningRoom/$c).
Philosopher 4[akka://system/user/dinningRoom/$i] entered. Given left(akka://syst
em/user/dinningRoom/$d) and right(akka://system/user/dinningRoom/$e).
Philosopher 3[akka://system/user/dinningRoom/$h] entered. Given left(akka://syst
em/user/dinningRoom/$c) and right(akka://system/user/dinningRoom/$d).
Philosopher 5[akka://system/user/dinningRoom/$j] entered. Given left(akka://syst
em/user/dinningRoom/$e) and right(akka://system/user/dinningRoom/$a).
Philosopher 3 waiting left chopstick)
Philosopher 4 waiting left chopstick)
Philosopher 2 waiting left chopstick)
Philosopher 1 waiting left chopstick)
Philosopher 5 waiting left chopstick)
Philosopher 1 got left chopstick.
Philosopher 3 got left chopstick.
Philosopher 4 got left chopstick.
Philosopher 2 got left chopstick.
Philosopher 5 got left chopstick.
##### 1 #####
Philosopher 2 thinking. (wait right chopstick)
Philosopher 1 thinking. (wait right chopstick)
Philosopher 1 waiting for 1 second
Philosopher 5 thinking. (wait right chopstick)
Philosopher 5 waiting for 1 second
Philosopher 3 thinking. (wait right chopstick)
Philosopher 3 waiting for 1 second
Philosopher 4 thinking. (wait right chopstick)
Philosopher 2 waiting for 1 second
Philosopher 4 waiting for 1 second
##### 2 #####
Philosopher 2 thinking. (wait right chopstick)
Philosopher 1 thinking. (wait right chopstick)
Philosopher 1 waiting for 2 second
Philosopher 3 thinking. (wait right chopstick)
Philosopher 4 thinking. (wait right chopstick)
Philosopher 4 waiting for 2 second
Philosopher 5 thinking. (wait right chopstick)
Philosopher 3 waiting for 2 second
Philosopher 2 waiting for 2 second
Philosopher 5 waiting for 2 second
##### 3 #####
Philosopher 2 thinking. (wait right chopstick)
Philosopher 2 waiting for 3 second
Philosopher 5 thinking. (wait right chopstick)
Philosopher 5 waiting for 3 second
Philosopher 3 thinking. (wait right chopstick)
Philosopher 3 waiting for 3 second
Philosopher 4 thinking. (wait right chopstick)
Philosopher 1 thinking. (wait right chopstick)
Philosopher 4 waiting for 3 second
Philosopher 1 waiting for 3 second
##### 4 #####
Philosopher 3 thinking. (wait right chopstick)
Philosopher 3 waiting for 4 second
Philosopher 1 thinking. (wait right chopstick)
Philosopher 2 thinking. (wait right chopstick)
Philosopher 2 waiting for 4 second
Philosopher 4 thinking. (wait right chopstick)
Philosopher 5 thinking. (wait right chopstick)
Philosopher 5 waiting for 4 second
Philosopher 4 waiting for 4 second
Philosopher 1 waiting for 4 second
##### 5 #####
Philosopher 2 thinking. (wait right chopstick)
Philosopher 2 waiting for 5 second
Philosopher 1 thinking. (wait right chopstick)
Philosopher 1 waiting for 5 second
Philosopher 4 thinking. (wait right chopstick)
Philosopher 4 waiting for 5 second
Philosopher 5 thinking. (wait right chopstick)
Philosopher 3 thinking. (wait right chopstick)
Philosopher 5 waiting for 5 second
Philosopher 3 waiting for 5 second
##### 6 #####
Philosopher 4 thinking. (wait right chopstick)
Philosopher 1 thinking. (wait right chopstick)
Philosopher 1 waiting for 6 second. Starving!!!
Philosopher 3 thinking. (wait right chopstick)
Philosopher 5 thinking. (wait right chopstick)
Philosopher 5 waiting for 6 second. Starving!!!
Philosopher 2 thinking. (wait right chopstick)
Philosopher 2 waiting for 6 second. Starving!!!
Philosopher 3 waiting for 6 second. Starving!!!
Philosopher 4 waiting for 6 second. Starving!!!
##### 7 #####
Philosopher 4 thinking. (wait right chopstick)
Philosopher 2 thinking. (wait right chopstick)
Philosopher 2 waiting for 7 second. Starving!!!
Philosopher 3 thinking. (wait right chopstick)
Philosopher 5 thinking. (wait right chopstick)
Philosopher 5 waiting for 7 second. Starving!!!
Philosopher 1 thinking. (wait right chopstick)
Philosopher 1 waiting for 7 second. Starving!!!
Philosopher 3 waiting for 7 second. Starving!!!
Philosopher 4 waiting for 7 second. Starving!!!
##### 8 #####
Philosopher 1 thinking. (wait right chopstick)
Philosopher 1 waiting for 8 second. Starving!!!
Philosopher 4 thinking. (wait right chopstick)
Philosopher 4 waiting for 8 second. Starving!!!
Philosopher 5 thinking. (wait right chopstick)
Philosopher 5 waiting for 8 second. Starving!!!
Philosopher 3 thinking. (wait right chopstick)
Philosopher 3 waiting for 8 second. Starving!!!
Philosopher 2 thinking. (wait right chopstick)
Philosopher 2 waiting for 8 second. Starving!!!
##### 9 #####
Philosopher 3 thinking. (wait right chopstick)
Philosopher 1 thinking. (wait right chopstick)
Philosopher 2 thinking. (wait right chopstick)
Philosopher 4 thinking. (wait right chopstick)
Philosopher 5 thinking. (wait right chopstick)
Philosopher 4 waiting for 9 second. Starving!!!
Philosopher 2 waiting for 9 second. Starving!!!
Philosopher 1 waiting for 9 second. Starving!!!
Philosopher 3 waiting for 9 second. Starving!!!
Philosopher 5 waiting for 9 second. Starving!!!
##### 10 #####
FINISH!
Philosopher 5 thinking. (wait right chopstick)
Philosopher 4 thinking. (wait right chopstick)
Philosopher 1 thinking. (wait right chopstick)
Philosopher 3 thinking. (wait right chopstick)
Philosopher 2 thinking. (wait right chopstick)
Philosopher 3 waiting for 10 second. Starving!!!
Philosopher 1 waiting for 10 second. Starving!!!
Philosopher 4 waiting for 10 second. Starving!!!
Philosopher 5 waiting for 10 second. Starving!!!
Philosopher 2 waiting for 10 second. Starving!!!
[success] Total time: 24 s, completed 06/07/2014 11:25:38 PM
```

좋지 않다. 모두 다 아사상태이다. 살펴보니 모두 다 왼쪽 젓가락을 부여잡고 굶어죽어 버렸다. 젓가락을 요청하는 시간을 약간씩 차이를 둬야 할 것 같다. 철학자 클래스를 좀 바꿔서 생각만 하는 시간을 좀 넣자. 즉, 초기상태를 생각상태로 두고 임의의 시간이 흐르고 나면 왼쪽 젓가락을 요청하게 하는 것이다.

## 임의 시간동안 생각하다 라멘을 먹는 철학자

초기상태를 생각하는 상태로 만들기 위해 `Status` 열거형에 `Thinking`을 추가한다. 그리고, 그 상태에 진입할 때 최대 5초까지 생각하도록 난수를 넣었다.

```
class Philosopher(index:Int, leftChopStick:ActorPath, rightChopStick:ActorPath) extends Actor {
  override def preStart() {
    println(s"Philosopher ${index}[${self.path}] entered. Given left(${leftChopStick}) and right(${rightChopStick}).")
  }

  object Status extends Enumeration {
    type Status = Value
    val Thinking, WaitLeft, WaitRight, Eating, ReleaseRight, ReleaseLeft = Value
  }
  import Status._
  
  val STARVING_THRES = 5
  val EATING_THRES = 3
  val THINKING_THRES = 5

  var status = Thinking
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
    status = WaitLeft
    println(s"Philosopher ${index} waiting left chopstick)")
    context.actorSelection(leftChopStick) ! "leftRequest"
  }

  def enterThinkingOrGetLeft() {
    thinkingTime = util.Random.nextInt(THINKING_THRES)
    if(thinkingTime == 0)
      enterLeftWaiting()
    else  {
      status = Thinking
      println(s"Philosopher ${index} thinking")
    }
  }

  def receive = { 
    case "start" =>  {
      enterThinkingOrGetLeft()
    }
    case "tick" => {
      if(status == Thinking) {
        thinkingTime -= 1
        if(thinkingTime == 0) {
          enterLeftWaiting()
        }
        else
          println(s"Philosopher ${index} thinking")
      } else if(status == WaitLeft) {
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
      enterThinkingOrGetLeft()
    }
  }
}
```

이름이 겹치는걸 막기 위해 패키지 이름을 바꾸고 이제 실행해 보면...

```
> run 5 10

Multiple main classes detected, select one to run:

 [1] SillySerious.ActorApp
 [2] Dinning2.ActorApp
 [3] HelloWorld
 [4] Dinning.ActorApp
 [5] ActorApp

Enter number: 2

[info] Running Dinning2.ActorApp 5 10
Chopstick 2 on the table
Chopstick 1 on the table
Chopstick 4 on the table
Chopstick 3 on the table
Chopstick 5 on the table
Room akka://system/user/dinningRoom created
START!
Philosopher 5[akka://system/user/dinningRoom/$j] entered. Given left(akka://syst
em/user/dinningRoom/$e) and right(akka://system/user/dinningRoom/$a).
Philosopher 1[akka://system/user/dinningRoom/$f] entered. Given left(akka://syst
em/user/dinningRoom/$a) and right(akka://system/user/dinningRoom/$b).
Philosopher 2[akka://system/user/dinningRoom/$g] entered. Given left(akka://syst
em/user/dinningRoom/$b) and right(akka://system/user/dinningRoom/$c).
Philosopher 3[akka://system/user/dinningRoom/$h] entered. Given left(akka://syst
em/user/dinningRoom/$c) and right(akka://system/user/dinningRoom/$d).
Philosopher 4[akka://system/user/dinningRoom/$i] entered. Given left(akka://syst
em/user/dinningRoom/$d) and right(akka://system/user/dinningRoom/$e).
Philosopher 3 thinking
Philosopher 2 waiting left chopstick)
Philosopher 1 thinking
Philosopher 5 thinking
Philosopher 4 waiting left chopstick)
Philosopher 2 got left chopstick.
Philosopher 4 got left chopstick.
##### 1 #####
Philosopher 1 thinking
Philosopher 3 thinking
Philosopher 2 thinking. (wait right chopstick)
Philosopher 4 thinking. (wait right chopstick)
Philosopher 4 waiting for 1 second
Philosopher 2 waiting for 1 second
Philosopher 4 got all chopsticks. start eating
Philosopher 5 thinking
Philosopher 2 got all chopsticks. start eating
##### 2 #####
Philosopher 5 thinking
Philosopher 3 waiting left chopstick)
Philosopher 4 eating. For 1 seconds.
Philosopher 1 waiting left chopstick)
Philosopher 2 eating. For 1 seconds.
Philosopher 1 got left chopstick.
##### 3 #####
Philosopher 5 thinking
Philosopher 3 thinking. (wait left chopstick)
Philosopher 3 waiting for 1 second
Philosopher 2 eating. For 2 seconds.
Philosopher 4 eating. For 2 seconds.
Philosopher 1 thinking. (wait right chopstick)
Philosopher 1 waiting for 1 second
##### 4 #####
Philosopher 1 thinking. (wait right chopstick)
Philosopher 1 waiting for 2 second
Philosopher 3 thinking. (wait left chopstick)
Philosopher 3 waiting for 2 second
Philosopher 5 waiting left chopstick)
Philosopher 4 eating. For 3 seconds.
Philosopher 2 eating. For 3 seconds.
Philosopher 2 releasing right chopstick
Philosopher 4 releasing right chopstick
Philosopher 2 releasing left chopstick
Philosopher 4 releasing left chopstick
Philosopher 2 thinking
Philosopher 4 waiting left chopstick)
Philosopher 4 got left chopstick.
##### 5 #####
Philosopher 3 thinking. (wait left chopstick)
Philosopher 3 waiting for 3 second
Philosopher 4 thinking. (wait right chopstick)
Philosopher 4 waiting for 1 second
Philosopher 1 thinking. (wait right chopstick)
Philosopher 1 waiting for 3 second
Philosopher 2 thinking
Philosopher 5 thinking. (wait left chopstick)
Philosopher 5 waiting for 1 second
Philosopher 1 got all chopsticks. start eating
Philosopher 4 got all chopsticks. start eating
Philosopher 3 got left chopstick.
##### 6 #####
Philosopher 2 thinking
Philosopher 3 thinking. (wait right chopstick)
Philosopher 3 waiting for 4 second
Philosopher 1 eating. For 1 seconds.
Philosopher 4 eating. For 1 seconds.
Philosopher 5 thinking. (wait left chopstick)
Philosopher 5 waiting for 2 second
##### 7 #####
Philosopher 5 thinking. (wait left chopstick)
Philosopher 1 eating. For 2 seconds.
Philosopher 3 thinking. (wait right chopstick)
Philosopher 3 waiting for 5 second
Philosopher 2 thinking
Philosopher 4 eating. For 2 seconds.
Philosopher 5 waiting for 3 second
##### 8 #####
Philosopher 3 thinking. (wait right chopstick)
Philosopher 3 waiting for 6 second. Starving!!!
Philosopher 1 eating. For 3 seconds.
Philosopher 1 releasing right chopstick
Philosopher 4 eating. For 3 seconds.
Philosopher 4 releasing right chopstick
Philosopher 5 thinking. (wait left chopstick)
Philosopher 2 waiting left chopstick)
Philosopher 5 waiting for 4 second
Philosopher 4 releasing left chopstick
Philosopher 1 releasing left chopstick
Philosopher 4 thinking
Philosopher 1 thinking
Philosopher 5 got left chopstick.
Philosopher 2 got left chopstick.
##### 9 #####
Philosopher 1 waiting left chopstick)
Philosopher 4 thinking
Philosopher 2 thinking. (wait right chopstick)
Philosopher 2 waiting for 1 second
Philosopher 3 thinking. (wait right chopstick)
Philosopher 3 waiting for 7 second. Starving!!!
Philosopher 5 thinking. (wait right chopstick)
Philosopher 5 waiting for 5 second
Philosopher 3 got all chopsticks. start eating
Philosopher 1 got left chopstick.
##### 10 #####
Philosopher 4 thinking
Philosopher 3 eating. For 1 seconds.
Philosopher 5 thinking. (wait right chopstick)
Philosopher 1 thinking. (wait right chopstick)
Philosopher 5 waiting for 6 second. Starving!!!
Philosopher 1 waiting for 1 second
FINISH!
Philosopher 2 thinking. (wait right chopstick)
Philosopher 2 waiting for 2 second
[success] Total time: 12 s, completed 06/07/2014 11:46:32 PM
```

## 결론

대충 1차로는 완성했다. 스멜스멜 나쁜 코드 냄새가 너무 많이 나긴 하지만... 가장 핵심적인 냄새를 말하자면 다음과 같다.

> _상태의 변화를 체크하는 것과 상태를 바꾸는 것이 if/case문 안에 있어서 일목요연하게 상태와 상태가 변하는 조건, 상태 변경시 일어나야 하는 액션을 알기가 어렵다._

다음엔 akka에서 액터의 상태를 바꿀 때 사용할 수 있는 방법을 생각해 보고, 코디네이터 액터에서 메시지를 보내는 전략을 직접 구현(Random.shuffle사용)했던 것을 akka가 제공하는 메시지 라우팅 기능을 활용하도록 변경할 것이다.

akka 액터관련 소스코드는 [PISActors리포](https://github.com/enshahar/PISActors)에 있다.
