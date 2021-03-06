package io.github.finagle.smtp

import com.twitter.finagle.Service
import com.twitter.util.{Await, Future}
import io.github.finagle.smtp.extension.Extensions.ExtensionList
import io.github.finagle.smtp.extension._
import io.github.finagle.smtp.extension.auth.{AuthMechanism, AuthRequest}
import io.github.finagle.smtp.extension.chunking.ChunkingReq.BeginDataChunk
import io.github.finagle.smtp.extension.pipelining.RequestGroup
import io.github.finagle.smtp.util.{SimpleTestService, TestError, TestReply}
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class EsmtpTest extends FunSuite  {
  import io.github.finagle.smtp.extension.Extension._
  import io.github.finagle.smtp.extension.ExtensionKeywords._

  val domain = "domain"
  def extensionsService(extensionString: String) = new Service[Request, Reply] {
    def apply(req: Request): Future[Reply] = Future.value(OK(domain + "\r\n" + extensionString))
  }

  test("allows only extensions that both client and server support") {
    val clientExtensions = ExtensionList (EightBitMime, Size())
    val serverExtensions = ExtensionList (EightBitMime, Chunking)

    val testService = extensionsService(serverExtensions.lines().mkString("\r\n"))

    val bothExtensions = Await result Esmtp.greet(testService, clientExtensions)

    assert(bothExtensions.asInstanceOf[ExtensionList].list.map(_.keyword).toSeq
      === Seq(EIGHTBITMIME))
  }

  test("domain is not treated as extension") {
    val withFakeExtension = ExtensionList (EightBitMime, Extension(domain))
    val serverExtensions = ExtensionList (EightBitMime, Chunking)

    val testService = extensionsService(serverExtensions.lines().mkString("\r\n"))

    val bothExtensions = Await result Esmtp.greet(testService, withFakeExtension)

    assert(!bothExtensions.asInstanceOf[ExtensionList]
      .list.map(_.keyword.toLowerCase).contains(domain))
  }

  test("sends HELO in case of failed EHLO") {
    var heloSent = false

    val testService = new Service[Request, Reply] {
      def apply(req: Request): Future[Reply] = req match {
        case Request.Hello => Future.exception(CommandNotImplemented("EHLO"))
        case Request.SimpleHello =>
          heloSent = true
          Future.value(OK("HELO"))
        case _ => Future.exception(new TestError)
      }
    }

    val extensions = Await result Esmtp.greet(testService, ExtensionList())

    assert(heloSent)
  }

  test("no extensions in case of failed EHLO") {
    val testService = new Service[Request, Reply] {
      def apply(req: Request): Future[Reply] = req match {
        case Request.Hello => Future.exception(CommandNotImplemented("EHLO"))
        case Request.SimpleHello => Future.value(OK("HELO"))
        case _ => Future.exception(new TestError)
      }
    }

    val extensions = Await result Esmtp.greet(testService, ExtensionList(EightBitMime))

    assert(extensions.list.isEmpty)
  }

  test("BINARYMIME not supported without CHUNKING") {
    val clientSupport = ExtensionList(BinaryMime)

    val testService = extensionsService("%s\r\n%s".format(BINARYMIME,CHUNKING))

    val extensions = Await result Esmtp.greet(testService, clientSupport)

    assert(!extensions.list.map(_.keyword).contains(BINARYMIME))
  }

  test("MAIL FROM parameters are omitted without server support") {
    val clientSupport = ExtensionList(EightBitMime, Size())

    val greetService = extensionsService("")
    val extensions = Await result Esmtp.greet(greetService, clientSupport)
    val extendedService = Esmtp(extensions) extend SimpleTestService

    val sender = MailingAddress("sender@send.com")
    val eightBitRequest = ExtendedMailingSession(sender).bodyEncoding(BodyEncoding.EightBit)
    val sizeRequest = ExtendedMailingSession(sender).messageSize(120)

    val eightBitReply = Await result extendedService(eightBitRequest)
    val sizeReply = Await result extendedService(sizeRequest)

    def fromTestReply(rep: Reply) = rep.asInstanceOf[TestReply].req.asInstanceOf[ExtendedMailingSession]

    assert(!fromTestReply(eightBitReply).ext.isDefinedAt("BODY"))
    assert(!fromTestReply(sizeReply).ext.isDefinedAt("SIZE"))
  }

  test("Extension commands are rejected without server support") {
    val clientSupport = ExtensionList(Auth, Chunking, Expn, Pipelining)

    val greetService = extensionsService("")
    val extensions = Await result Esmtp.greet(greetService, clientSupport)
    val extendedService = Esmtp(extensions) extend SimpleTestService

    val mailbox = MailingAddress("mb@send.com")
    val authRequest = AuthRequest(AuthMechanism.plain("login", "password"))
    val chunkingRequest = BeginDataChunk(120)
    val expnRequest = Request.ExpandMailingList(mailbox)
    val pipeliningRequest = RequestGroup(Request.Hello)

    Seq(authRequest, chunkingRequest, expnRequest, pipeliningRequest) map { req =>
        Await result (
          extendedService(req) onSuccess { _ =>
            fail("should have rejected request: %s" format req.toString)
          } handle {
            case _ =>
          }
        )
      }
    }
}
