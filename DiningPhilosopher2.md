---
layout: post
title: [스칼라] akka 버전 철학자의 만찬(Dining Philosopher) - 2. 상태 정리와 라우터 도입
tags:
- scala
- 스칼라
- 액터
- akka
- 철학자의 만찬
- Dining Philosopher
---
# [스칼라] akka 버전 철학자의 만찬(Dining Philosopher) - 2

[앞의글](http://www.enshahar.me/2014/07/akka-dining-philosopher-1.html)에서 만들었던 프로그램에서 두가지를 수정해 보자.

* 상태를 변수에 넣어서 관리하던 것 - 상태를 변수에 넣고, 처리 로직은 case나 if문에 남아있으면 상태 전이시 상태 처리와 변수 값 업데이트등이 서로 달라질 수도 있고, 일목요연하게 상태별 동작을 이해하기 어려울 수 있다. 또한, 어떤 상태에서 처리해야 할 일을 추가하고 싶을 때도 어디 넣어야 할지 이해하기 어려울 수도 있다. GoF의 디자인 패턴 책에서는 이런 경우 사용할 [상태 패턴](http://en.wikipedia.org/wiki/State_pattern)이 있다. akka에서는 이런 경우를 대비해 `become`/`unbecome`이 있다. 
    * `become`: `Actor.Receive`타입의 함수값(function value)를 인자로 받으며, 이 함수를 호출하고 나면 그 이후로 도착하는 메시지는 모두 `become`에 제공한 함수값에서 처리한다. `Actor.Receive` 타입은 실제로는 `PartialFunction[Any, Unit]`이며, 스칼라에서는 패턴 매칭이 이런 타입을 만들어낼 수 있다.
    * `unbecome`: 액터 내부에 `become`한 상태를 스택으로 유지한다. 따라서 직전 상태로 돌아가는게 목적이라면 `unbecome`만으로 쉽게 이전 상태(이전 메시지 핸들러)로 돌아갈 수 있다.
* 코디네이터(`DiningRoom`)가 라우팅을 직접 하던 부분: akka는 여러 라우팅 전략을 제공하며, 그 중에 브로드캐스팅 전략도 있다. 이를 활용해 보자.

## 젓가락을 become() 을 사용하도록 변경하기

`become`을 사용하기로 하면, 더이상 상태 변수가 필요 없다. 케이스문을 복사해서 두가지로 나누자. 하나는 미사용상태인 젓가락의 메시지 처리 함수이고 다른 하나는 사용상태인 젓가락의 메시지 처리 함수로 나눈다.

```scala
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
```

`occupiedReceive`에 붙은 `Receive` 타입은 액터에 있는 타입별명(alias)으로, 실제로는 `PartialFunction[Any, Unit]`이다. 

`context.become(occupiedReceive, false)`에서 두번째 인자 `false`는 현재 메시지 핸들러를 스택에 남겨두라는 의미이다. `false`를 지정하지 않으면 스택의 맨 위의 핸들러를 바꿔치기 해 버리기 때문에, unbecome하면 문제가 생긴다(잘 돌지만 메시지를 그냥 모두 무시하기 때문에 우리가 의도한 대로 동작하지 않는다).

바꾸고 돌려보면 잘 돈다. 이제 철학자 클래스를 살펴볼 차례이다.

## 철학자 클래스 receive사용하게 바꾸기

상태 변수 status를 없애고 차근차근 become을 사용해 변경하면 클래스를 다음과 같이 정리할 수 있다.

```scala
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
```

변경 자체는 평이하다. 변경하고 난 코드가 더 깔끔한지는 잘 모르겠다. T.T 실행 결과는 따로 표시하지는 않겠다.

문득 드는 의문은 다음과 같다.

> "tick" 메시지가 "tick"을 처리하지 않는 상태(releasingRight, releasingLeft 등)에 들어오면 어떻게 될까? 메시지를 잃어버리나, 아니면 그 다음 "tick"을 처리하는 메시지 핸들러가 해당 "tick"메시지를 처리하나?

또한, 출력에서 한가지 아쉬운점은 순서가 뒤죽박죽이라서 상황을 파악하기 힘들다는 것이다. 또한, 출력 부분과 로직부분이 섞여 있어서 이래저래 곤란하다. 이를 개선할 방법도 다음에 찾아보겠다. 

우선은 코디 액터(DinningRoom)를 akka 라우팅을 사용하게 바꾸자. 

## akka 라우팅 사용하기

라우팅을 위해서는 라우터(Router)를 만들어야 한다. 라우터 생성자 중 쓸만한 것이 뭐가 있을까 보면, `new
Router(logic: RoutingLogic, routees: IndexedSeq[Routee] = ...)`가 있다.

* `RoutingLogic`은 메시지를 라우터가 받아서 어떤 routee에 전달할지 결정하는 로직이다. akka가 기본제공하는 로직으로는 다음과 같은 것이 있다. 이름이 대충 무슨 로직인지 설명해주므로 자세히는 안 쓸 것이다.
    * akka.routing.RoundRobinRoutingLogic
    * akka.routing.RandomRoutingLogic
    * akka.routing.SmallestMailboxRoutingLogic
    * akka.routing.BroadcastRoutingLogic
    * akka.routing.ScatterGatherFirstCompletedRoutingLogic
    * akka.routing.ConsistentHashingRoutingLogic
* 이 생성자의 두번째 인자는 `IndexedSeq[Routee]`이다. `Routee` 문서에서 서브클래스를 찾아보면 `ActorRefRoutee`이 있다. 액터를 넣어야 하니까 이거면 될것 같다. `IndexedSeq`는 벡터나 배열이면 된다. 

일단 라우터를 만드는 부분을 빼서 살펴보면 다음과 같다.
```scala
  val router = akka.routing.Router(akka.routing.BroadcastRoutingLogic(),   
                                  (members++chopSticks).map(akka.routing.ActorRefRoutee(_)))
```scala

이렇게 하면 모든 젓가락과 철학자를 포함하는 라우터를 만들 수 있다.

이제 이를 사용해 메시지를 보내는 부분은 다음과 같다.
```scala
router.route("tick", sender())
```

`route` 메서드는 첫번째 인자로 메시지, 두번째 인자로 송신객체를 받는다. 여기서는 코디가 받은 메시지를 송신했던 객체를 다시 송신객체로 보낸다. 

이를 반영한 `DiningRoom` 전체 코드는 다음과 같다.
```scala
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
```

## 전체 소스
여기까지 모두 적용한 소스는 [PISActors리포](https://github.com/enshahar/PISActors)에 있다. 파일 이름은 DiningRandomWithBecomeAndBroadcast.scala이다.

## 결론

`become`를 사용해 상태 변경을 좀더 집중적으로 관리할 수 있다. 라우터(Router)를 사용하면 메시지 송신시 다양한 전략을 선택할 수 있다. 기회가 닿으면 한번 좀 더 복잡한 예제로 라우팅이 필요한 경우를 만들어 볼 것이다.

다음에는 다음 문제를 한번 테스트해 보자. 구글링등을 통해 해답을 찾아보고 간단하게 학습용 클래스를 만들어서 시험해 볼 것이다. 

> "tick" 메시지가 "tick"을 처리하지 않는 상태(releasingRight, releasingLeft 등)에 들어오면 어떻게 될까? 메시지를 잃어버리나, 아니면 그 다음 "tick"을 처리하는 메시지 핸들러가 해당 "tick"메시지를 처리하나?
