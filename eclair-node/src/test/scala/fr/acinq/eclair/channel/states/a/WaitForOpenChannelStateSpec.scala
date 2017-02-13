package fr.acinq.eclair.channel.states.a

import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.eclair.{TestkitBaseClass, TestConstants}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.wire.{Error, OpenChannel}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

/**
  * Created by PM on 05/07/2016.
  */
@RunWith(classOf[JUnitRunner])
class WaitForOpenChannelStateSpec extends TestkitBaseClass {

  type FixtureParam = Tuple4[TestFSMRef[State, Data, Channel], TestProbe, TestProbe, TestProbe]

  override def withFixture(test: OneArgTest) = {
    val alice2bob = TestProbe()
    val bob2alice = TestProbe()
    val alice2blockchain = TestProbe()
    val bob2blockchain = TestProbe()
    val relayer = TestProbe()
    val router = TestProbe()
    val alice: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(alice2bob.ref, alice2blockchain.ref, router.ref, relayer.ref, Alice.channelParams, Bob.id))
    val bob: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(bob2alice.ref, bob2blockchain.ref, router.ref, relayer.ref, Bob.channelParams, Alice.id))
    alice ! INPUT_INIT_FUNDER(TestConstants.fundingSatoshis, TestConstants.pushMsat)
    bob ! INPUT_INIT_FUNDEE()
    within(30 seconds) {
      awaitCond(bob.stateName == WAIT_FOR_OPEN_CHANNEL)
    }
    test((bob, alice2bob, bob2alice, bob2blockchain))
  }

  test("recv OpenChannel") { case (bob, alice2bob, bob2alice, bob2blockchain) =>
    within(30 seconds) {
      alice2bob.expectMsgType[OpenChannel]
      alice2bob.forward(bob)
      awaitCond(bob.stateName == WAIT_FOR_FUNDING_CREATED)
    }
  }

  test("recv OpenChannel (reserve too high)") { case (bob, alice2bob, bob2alice, bob2blockchain) =>
    within(30 seconds) {
      val open = alice2bob.expectMsgType[OpenChannel]
      // 30% is huge, recommended ratio is 1%
      val reserveTooHigh = (0.3 * TestConstants.fundingSatoshis).toLong
      bob ! open.copy(channelReserveSatoshis = reserveTooHigh)
      val error = bob2alice.expectMsgType[Error]
      assert(new String(error.data) === "requirement failed: channelReserveSatoshis too high: ratio=0.3 max=0.05")
      awaitCond(bob.stateName == CLOSED)
    }
  }

  test("recv Error") { case (bob, alice2bob, bob2alice, bob2blockchain) =>
    within(30 seconds) {
      bob ! Error(0, "oops".getBytes())
      awaitCond(bob.stateName == CLOSED)
    }
  }

  test("recv CMD_CLOSE") { case (bob, alice2bob, bob2alice, bob2blockchain) =>
    within(30 seconds) {
      bob ! CMD_CLOSE(None)
      awaitCond(bob.stateName == CLOSED)
    }
  }

}