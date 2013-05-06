package play.api.libs.iteratee

import org.specs2.mutable._

import java.io.OutputStream
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import play.api.libs.iteratee.Execution.Implicits.{ defaultExecutionContext => dec }
import scala.concurrent.{Promise, Future, Await}
import scala.concurrent.duration.Duration
import scala.language.reflectiveCalls

object EnumeratorsSpec extends Specification with IterateeSpecification {

  "Enumerator's interleave" should {

    "mix it with another enumerator into one" in {
      mustExecute(8) { foldEC =>
        val e1 = Enumerator(List(1), List(3), List(5), List(7))
        val e2 = Enumerator(List(2), List(4), List(6), List(8))
        val e = e1 interleave e2
        val kk = e |>>> Iteratee.fold(List.empty[Int])((r, e: List[Int]) => r ++ e)(foldEC)
        val result = Await.result(kk, Duration.Inf)
        println("interleaved enumerators result is: " + result)
        result.diff(Seq(1, 2, 3, 4, 5, 6, 7, 8)) must equalTo(Seq())
      }
    }

    "yield when both enumerators EOF" in {
      mustExecute(8) { foldEC =>
        val e1 = Enumerator(List(1), List(3), List(5), List(7)) >>> Enumerator.enumInput(Input.EOF)
        val e2 = Enumerator(List(2), List(4), List(6), List(8)) >>> Enumerator.enumInput(Input.EOF)
        val e = e1 interleave e2
        val kk = e |>>> Iteratee.fold(List.empty[Int])((r, e: List[Int]) => r ++ e)(foldEC)
        val result = Await.result(kk, Duration.Inf)
        result.diff(Seq(1, 2, 3, 4, 5, 6, 7, 8)) must equalTo(Seq())
      }
    }

    "yield when iteratee is done!" in {
      mustExecute(7) { foldEC =>
        val e1 = Enumerator(List(1), List(3), List(5), List(7))
        val e2 = Enumerator(List(2), List(4), List(6), List(8))
        val e = e1 interleave e2
        val kk = e |>>> Enumeratee.take(7) &>> Iteratee.fold(List.empty[Int])((r, e: List[Int]) => r ++ e)(foldEC)
        val result = Await.result(kk, Duration.Inf)
        result.length must equalTo(7)
      }
    }

    "not necessarily go alternatively between two enumerators" in {
      mustExecute(1, 2) { (onDoneEC, unfoldEC) =>
        val firstDone = Promise[Unit]
        val e1 = Enumerator(1, 2, 3, 4).onDoneEnumerating(firstDone.success(Unit))(onDoneEC)
        val e2 = Enumerator.unfoldM[Boolean, Int](true) { first => if (first) firstDone.future.map(_ => Some(false, 5)) else Future.successful(None) }(unfoldEC)
        val result = Await.result((e1 interleave e2) |>>> Iteratee.getChunks[Int], Duration.Inf)
        result must_== Seq(1, 2, 3, 4, 5)
      }
    }

  }

  "Enumerator.enumerate " should {
    "generate an Enumerator from a singleton Iterator" in {
      mustExecute(1) { foldEC =>
        val iterator = scala.collection.Iterator.single[Int](3)
        val futureOfResult = Enumerator.enumerate(iterator) |>>>
          Enumeratee.take(1) &>>
          Iteratee.fold(List.empty[Int])((r, e: Int) => e :: r)(foldEC)
        val result = Await.result(futureOfResult, Duration.Inf)
        result(0) must equalTo(3)
        result.length must equalTo(1)
      }
    }

    "take as much element as in the iterator in the right order" in {
      mustExecute(50) { foldEC =>
        val iterator = scala.collection.Iterator.range(0, 50)
        val futureOfResult = Enumerator.enumerate(iterator) |>>>
          Enumeratee.take(100) &>>
          Iteratee.fold(Seq.empty[Int])((r, e: Int) => r :+ e)(foldEC)
        val result = Await.result(futureOfResult, Duration.Inf)
        result.length must equalTo(50)
        result(0) must equalTo(0)
        result(49) must equalTo(49)
      }
    }
    "work with Seq too" in {
      mustExecute(6) { foldEC =>
        val seq = List(1, 2, 3, 7, 42, 666)
        val futureOfResult = Enumerator.enumerate(seq) |>>>
          Enumeratee.take(100) &>>
          Iteratee.fold(Seq.empty[Int])((r, e: Int) => r :+ e)(foldEC)
        val result = Await.result(futureOfResult, Duration.Inf)
        result.length must equalTo(6)
        result(0) must equalTo(1)
        result(4) must equalTo(42)
      }
    }
  }

  /*"Enumerator's PatchPanel" should {

  "allow to patch in different Enumerators" in {
      import play.api.libs.concurrent.Promise
    val pp = Promise[Concurrent.PatchPanel[Int]]()
    val e = Concurrent.patchPanel[Int](p => pp.redeem(p))
    val i1 = Iteratee.fold[Int,Int](0){(s,i) => println(i);s+i}
    val sum = e |>> i1
    val p = pp.future.await.get
    p.patchIn(Enumerator(1,2,3,4))
    p.patchIn(Enumerator.eof)
    sum.flatMap(_.run).value1.get must equalTo(10)
  }

}*/
  
  "Enumerator" should {
    
    "call onDoneEnumerating callback" in {
      mustExecute(1) { onDoneEC =>
        val count = new java.util.concurrent.atomic.AtomicInteger()
        mustEnumerateTo(1, 2, 3)(Enumerator(1, 2, 3).onDoneEnumerating(count.incrementAndGet())(onDoneEC))
        count.get() must equalTo(1)
      }
    }
    
    "transform input elements with map" in {
      mustExecute(3) { mapEC =>
        mustEnumerateTo(2, 4, 6)(Enumerator(1, 2, 3).map(_ * 2)(mapEC))
      }
    }
    
    "transform input with map" in {
      mustExecute(3) { mapEC =>
        mustEnumerateTo(2, 4, 6)(Enumerator(1, 2, 3).mapInput(_.map(_ * 2))(mapEC))
      }
    }
    
    "be transformed to another Enumerator using flatMap" in {
      mustExecute(3, 30) { (flatMapEC, foldEC) =>
        val e = Enumerator(10, 20, 30).flatMap(i => Enumerator((i until i + 10): _*))(flatMapEC)
        val it = Iteratee.fold[Int, Int](0)((sum, x) => sum + x)(foldEC)
        Await.result(e |>>> it, Duration.Inf) must equalTo((10 until 40).sum)
      }
    }

  }

  "Enumerator.generateM" should {
    "generate a stream of values until the expression is None" in {
      mustExecute(12, 11) { (generateEC, foldEC) =>
        val a = (0 to 10).toList
        val it = a.iterator

        val enumerator = Enumerator.generateM(Future(if (it.hasNext) Some(it.next()) else None))(generateEC)

        Await.result(enumerator |>>> Iteratee.fold[Int, String]("")(_ + _)(foldEC), Duration.Inf) must equalTo("012345678910")
      }
    }

    "Can be composed with another enumerator (doesn't send EOF)" in {
      mustExecute(12, 12) { (generateEC, foldEC) =>
        val a = (0 to 10).toList
        val it = a.iterator

        val enumerator = Enumerator.generateM(Future(if (it.hasNext) Some(it.next()) else None))(generateEC) >>> Enumerator(12)

        Await.result(enumerator |>>> Iteratee.fold[Int, String]("")(_ + _)(foldEC), Duration.Inf) must equalTo("01234567891012")
      }
    }

  }

  "Enumerator.callback1" should {
    "generate a stream of values until the expression is None" in {
      mustExecute(5) { callbackEC =>
        val it = (1 to 3).iterator // FIXME: Probably not thread-safe
        val completeCount = new AtomicInteger(0)
        val completeDone = new CountDownLatch(1)
        val errorCount = new AtomicInteger(0)
        val enumerator = Enumerator.fromCallback1(
            b => Future(if (it.hasNext) Some((b, it.next())) else None),
            () => {
              completeCount.incrementAndGet()
              completeDone.countDown()
            },
            (_: String, _: Input[(Boolean, Int)]) => errorCount.incrementAndGet())(callbackEC)
        mustEnumerateTo((true, 1), (false, 2), (false, 3))(enumerator)
        completeDone.await(30, TimeUnit.SECONDS) must beTrue
        completeCount.get() must equalTo(1)
        errorCount.get() must equalTo(0)
      }
    }
  }

  "Enumerator.unfoldM" should {
    "Can be composed with another enumerator (doesn't send EOF)" in {
      mustExecute(12, 12) { (foldEC, unfoldEC) =>
        val enumerator = Enumerator.unfoldM[Int, Int](0)(s => Future(if (s > 10) None else Some((s + 1, s + 1))))(unfoldEC) >>> Enumerator(12)

        Await.result(enumerator |>>> Iteratee.fold[Int, String]("")(_ + _)(foldEC), Duration.Inf) must equalTo("123456789101112")
      }
    }

  }
  
  "Enumerator.repeat" should {
    "supply input from a by-name arg" in {
      mustExecute(3) { repeatEC =>
        val count = new AtomicInteger(0)
        val fut = Enumerator.repeat(count.incrementAndGet())(repeatEC) |>>> (Enumeratee.take(3) &>> Iteratee.getChunks[Int])
        Await.result(fut, Duration.Inf) must equalTo(List(1, 2, 3))
      }
    }
  }

  "Enumerator.repeatM" should {
    "supply input from a by-name arg" in {
      mustExecute(3) { repeatEC =>
        val count = new AtomicInteger(0)
        val fut = Enumerator.repeatM(Future.successful(count.incrementAndGet()))(repeatEC) |>>> (Enumeratee.take(3) &>> Iteratee.getChunks[Int])
        Await.result(fut, Duration.Inf) must equalTo(List(1, 2, 3))
      }
    }
  }

  "Enumerator.imperative" should {
    "allow input to be pushed" in {
      mustExecute(1) { (callbackEC) =>
        val startCount = new AtomicInteger()
        val completeCount = new AtomicInteger()
        val errorCount = new AtomicInteger()
        val allPushesReceived = new CountDownLatch(3)
        @volatile
        var pushed = List[Int]()

        val e = Enumerator.imperative(
            () => startCount.incrementAndGet(),
            () => completeCount.incrementAndGet(),
            (_: String, _: Input[Int]) => errorCount.incrementAndGet()) (callbackEC)
            
        val i = Iteratee.foreach[Int] { e =>
          pushed = pushed :+ e
          allPushesReceived.countDown()
        }(dec)

        val waitTime = scala.concurrent.duration.Duration(5, scala.concurrent.duration.SECONDS)
        Await.result(Future(e(i))(dec), waitTime)

        e.push(1) must equalTo(true)
        e.push(2) must equalTo(true)
        e.push(3) must equalTo(true)
        e.close()
        allPushesReceived.await(30, TimeUnit.SECONDS) must beTrue
        pushed must equalTo(List(1, 2, 3))
        startCount.get() must equalTo(1)
        completeCount.get() must equalTo(0) // Only called if Iteratee is done
        errorCount.get() must equalTo(0)
      }
    }
  }

  "Enumerator.pushee" should {
    "allow input to be pushed" in {
      mustExecute(1) { (callbackEC) =>
        val startCount = new AtomicInteger()
        val completeCount = new AtomicInteger()
        val errorCount = new AtomicInteger()
        val startDone = new CountDownLatch(1)
        @volatile
        var pushee: Enumerator.Pushee[Int] = null
        @volatile
        var pushed = List[Int]()
        val allPushesReceived = new CountDownLatch(3)

        val e = Enumerator.pushee(
          (p: Enumerator.Pushee[Int]) => {
            startCount.incrementAndGet()
            pushee = p
            startDone.countDown()
          },
          () => {
            completeCount.incrementAndGet()
          },
          (_: String, _: Input[Int]) => errorCount.incrementAndGet())(callbackEC)

        val i = Iteratee.foreach[Int] { e =>
          pushed = pushed :+ e
          allPushesReceived.countDown()
        }(dec)

        val waitTime = scala.concurrent.duration.Duration(5, scala.concurrent.duration.SECONDS)
        Await.result(Future(e(i))(dec), waitTime)

        startDone.await(30, TimeUnit.SECONDS) must beTrue
        pushee.push(1)
        pushee.push(2)
        pushee.push(3)
        pushee.close()

        allPushesReceived.await(30, TimeUnit.SECONDS) must beTrue
        pushed must equalTo(List(1, 2, 3))
        startCount.get() must equalTo(1)
        completeCount.get() must equalTo(0) // Only called if Iteratee is done
        errorCount.get() must equalTo(0)
      }
    }
  }

  "Enumerator.outputStream" should {
    "produce the same value written in the OutputStream" in {
      mustExecute(1, 2) { (outputEC, foldEC) =>
        val a = "FOO"
        val b = "bar"
        val enumerator = Enumerator.outputStream { outputStream =>
          outputStream.write(a.toArray.map(_.toByte))
          outputStream.write(b.toArray.map(_.toByte))
          outputStream.close()
        }(outputEC)
        val promise = (enumerator |>>> Iteratee.fold[Array[Byte], Array[Byte]](Array[Byte]())(_ ++ _)(foldEC))
        Await.result(promise, Duration.Inf).map(_.toChar).foldLeft("")(_ + _) must equalTo(a + b)
      }
    }

    "not block" in {
      mustExecute(1) { outputEC =>
        var os: OutputStream = null
        val osReady = new CountDownLatch(1)
        val enumerator = Enumerator.outputStream { o => os = o; osReady.countDown() }(outputEC)
        val promiseIteratee = Promise[Iteratee[Array[Byte], Array[Byte]]]
        val future = enumerator |>>> Iteratee.flatten(promiseIteratee.future)
        osReady.await(30, TimeUnit.SECONDS) must beTrue
        // os should now be set
        os.write("hello".getBytes)
        os.write(" ".getBytes)
        os.write("world".getBytes)
        os.close()
        promiseIteratee.success(Iteratee.consume[Array[Byte]]())
        Await.result(future, Duration("10s")) must_== "hello world".getBytes
      }
    }
  }

}
