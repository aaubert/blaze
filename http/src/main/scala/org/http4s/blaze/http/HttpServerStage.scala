package org.http4s.blaze.http


import java.nio.charset.StandardCharsets

import org.http4s.blaze.pipeline.Command.{OutboundCommand}
import org.http4s.blaze.pipeline.{Command => Cmd, _}
import org.http4s.blaze.util.Execution._
import org.http4s.websocket.WebsocketBits.WebSocketFrame
import org.http4s.websocket.WebsocketHandshake
import scala.util.control.NonFatal
import scala.util.{Failure, Success}
import scala.concurrent.Future
import scala.collection.mutable.ArrayBuffer
import org.http4s.blaze.http.http_parser.Http1ServerParser

import org.http4s.blaze.http.http_parser.BaseExceptions.BadRequest
import org.http4s.blaze.http.websocket.WebSocketDecoder


import java.util.Date
import java.nio.ByteBuffer

import org.http4s.blaze.util.BufferTools

class HttpServerStage(maxReqBody: Int)(handleRequest: HttpService) extends Http1ServerParser with TailStage[ByteBuffer] {
  import HttpServerStage.RouteResult._

  private implicit def ec = trampoline

  val name = "HTTP/1.1_Stage"

  private var uri: String = null
  private var method: String = null
  private var minor: Int = -1
  private var major: Int = -1
  private var headers = new ArrayBuffer[(String, String)]
  
  /////////////////////////////////////////////////////////////////////////////////////////

  // Will act as our loop
  override def stageStartup() {
    logger.info("Starting HttpStage")
    requestLoop()
  }

  private def requestLoop(): Unit = {
    channelRead().onComplete {
      case Success(buff) => readLoop(buff)
      case Failure(t)  =>
        println("Failure: " + t)
        val command = t match {
          case Cmd.EOF => println("Received EOF"); Cmd.Disconnect
          case e       => Cmd.Error(t)
        }

        shutdownWithCommand(command)
    }
  }

  private def readLoop(buff: ByteBuffer): Unit = {
    logger.trace {
      buff.mark()
      val msg = StandardCharsets.UTF_8.decode(buff)
      buff.reset()

      s"RequestLoop received buffer $buff. Request:\n$msg"
    }

    try {
      if (!requestLineComplete() && !parseRequestLine(buff)) {
        requestLoop()
        return
      }

      if (!headersComplete() && !parseHeaders(buff)) {
        requestLoop()
        return
      }

      // TODO: need to check if we need a Host header or otherwise validate the request
      // we have enough to start the request
      gatherBody(buff, new ArrayBuffer[ByteBuffer]).onComplete {
        case Success(b) =>
          val hdrs = headers
          headers = new ArrayBuffer[(String, String)](hdrs.size + 10)
          runRequest(b, hdrs)
        case Failure(t) => shutdownWithCommand(Cmd.Disconnect)
      }
    }
    catch { case t: Throwable   => shutdownWithCommand(Cmd.Disconnect) }
  }

  private def resetStage() {
    reset()
    uri = null
    method = null
    minor = -1
    major = -1
    headers.clear()
  }

  private def runRequest(buffer: ByteBuffer, reqHeaders: Headers): Unit = {
    try handleRequest(method, uri, reqHeaders, buffer).flatMap {
      case r: SimpleHttpResponse    => handleHttpResponse(r, reqHeaders)
      case WSResponse(stage)        => handleWebSocket(reqHeaders, stage)
    }.onComplete {       // See if we should restart the loop
      case Success(Reload)          => resetStage(); requestLoop()
      case Success(Close)           => shutdownWithCommand(Cmd.Disconnect)
      case Success(Upgrade)         => // NOOP don't need to do anything
      case Failure(t: BadRequest)   => badRequest(t)
      case Failure(t)               => shutdownWithCommand(Cmd.Error(t))
      case Success(other) =>
        logger.error("Shouldn't get here: " + other)
        shutdownWithCommand(Cmd.Disconnect)
    }
    catch {
      case NonFatal(e) =>
        logger.error(e)("Error during `handleRequest` of HttpServerStage")
        val body = ByteBuffer.wrap("Internal Service Error".getBytes(StandardCharsets.ISO_8859_1))
        handleHttpResponse(SimpleHttpResponse("OK", 200, Nil, body), reqHeaders).onComplete { _ =>
          shutdownWithCommand(Cmd.Error(e))
        }
    }
  }

  /** Deal with route responses of standard HTTP form */
  private def handleHttpResponse(resp: SimpleHttpResponse, reqHeaders: Headers): Future[RouteResult] = {
    val sb = new StringBuilder(512)
    sb.append("HTTP/").append(1).append('.')
      .append(minor).append(' ').append(resp.code)
      .append(' ').append(resp.status).append('\r').append('\n')

    val keepAlive = isKeepAlive(reqHeaders)

    if (!keepAlive) sb.append("Connection: close\r\n")
    else if (minor == 0 && keepAlive) sb.append("Connection: Keep-Alive\r\n")

    renderHeaders(sb, resp.headers, resp.body.remaining())

    val messages = Array(ByteBuffer.wrap(sb.result().getBytes(StandardCharsets.ISO_8859_1)), resp.body)

    channelWrite(messages).map(_ => if (keepAlive) Reload else Close)(directec)
  }

  /** Deal with route response of WebSocket form */
  private def handleWebSocket(reqHeaders: Headers, wsBuilder: LeafBuilder[WebSocketFrame]): Future[RouteResult] = {
    val sb = new StringBuilder(512)
    WebsocketHandshake.serverHandshake(reqHeaders) match {
      case Left((i, msg)) =>
        logger.info(s"Invalid handshake: $i: $msg")
        sb.append("HTTP/1.1 ").append(i).append(' ').append(msg).append('\r').append('\n')
          .append('\r').append('\n')

        channelWrite(ByteBuffer.wrap(sb.result().getBytes(StandardCharsets.ISO_8859_1))).map(_ => Close)

      case Right(hdrs) =>
        logger.info("Starting websocket request")
        sb.append("HTTP/1.1 101 Switching Protocols\r\n")
        hdrs.foreach { case (k, v) => sb.append(k).append(": ").append(v).append('\r').append('\n') }
        sb.append('\r').append('\n')

        // write the accept headers and reform the pipeline
        channelWrite(ByteBuffer.wrap(sb.result().getBytes(StandardCharsets.ISO_8859_1))).map{ _ =>
          logger.debug("Switching pipeline segments for upgrade")
          val segment = wsBuilder.prepend(new WebSocketDecoder(false))
          this.replaceInline(segment)
          Upgrade
        }
    }
  }

  private def badRequest(msg: BadRequest) {
    val sb = new StringBuilder(512)
    sb.append("HTTP/").append(1).append('.')
      .append(minor).append(' ').append(400)
      .append(' ').append("Bad Request").append('\r').append('\n').append('\r').append('\n')

    channelWrite(ByteBuffer.wrap(sb.result().getBytes(StandardCharsets.ISO_8859_1)))
      .onComplete(_ => shutdownWithCommand(Cmd.Disconnect))
  }

  private def renderHeaders(sb: StringBuilder, headers: Traversable[(String, String)], length: Int) {
    headers.foreach { case (k, v) =>
      // We are not allowing chunked responses at the moment, strip our Chunked-Encoding headers
      if (!k.equalsIgnoreCase("Transfer-Encoding") && !k.equalsIgnoreCase("Content-Length")) {
        sb.append(k)
        if (v.length > 0) sb.append(": ").append(v).append('\r').append('\n')
      }
    }
    // Add our length header last
    sb.append(s"Content-Length: ").append(length).append('\r').append('\n')
    sb.append('\r').append('\n')
  }

  private def gatherBody(buffer: ByteBuffer, buffers: ArrayBuffer[ByteBuffer]): Future[ByteBuffer] = {
    if (!contentComplete()) {
      buffers += parseContent(buffer)
      channelRead().flatMap(gatherBody(_, buffers))
    } else {
      val total = buffers.size match {
        case 0 => BufferTools.emptyBuffer
        case 1 => buffers.head
        case _ =>
          val sz = buffers.foldLeft(0)(_ + _.remaining())
          val b = BufferTools.allocate(sz)
          buffers.foreach(b.put)
          b.flip()
          b
      }
      Future.successful(total)
    }
  }

  private def shutdownWithCommand(cmd: OutboundCommand): Unit = {
    stageShutdown()
    sendOutboundCommand(cmd)
  }

  private def isKeepAlive(headers: Headers): Boolean = {
    val h = headers.find {
      case ("Connection", _) => true
      case _ => false
    }

    if (h.isDefined) {
      if (h.get._2.equalsIgnoreCase("Keep-Alive")) true
      else if (h.get._2.equalsIgnoreCase("close")) false
      else if (h.get._2.equalsIgnoreCase("Upgrade")) true
      else { logger.info(s"Bad Connection header value: '${h.get._2}'. Closing after request."); false }
    }
    else if (minor == 0) false
    else true
  }

  override protected def stageShutdown(): Unit = {
    logger.info("Shutting down HttpPipeline at " + new Date())
    shutdownParser()
  }

  protected def headerComplete(name: String, value: String): Boolean = {
    logger.trace(s"Received header '$name: $value'")
    headers += ((name, value))
    false
  }

  protected def submitRequestLine(methodString: String,
                                  uri: String,
                                  scheme: String,
                                  majorversion: Int,
                                  minorversion: Int): Boolean = {

    logger.trace(s"Received request($methodString $uri $scheme/$majorversion.$minorversion)")

    this.uri = uri
    this.method = methodString
    this.major = majorversion
    this.minor = minorversion
    false
  }
}

private object HttpServerStage {

  object RouteResult extends Enumeration {
    type RouteResult = Value
    val Reload, Close, Upgrade = Value
  }
}
