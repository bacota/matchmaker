package com.vivi.engine

import java.io.{BufferedInputStream, DataInputStream, EOFException, IOException, InputStream, OutputStream}
import java.net.{InetAddress, ServerSocket, Socket, URI}
import java.nio.charset.StandardCharsets.{ISO_8859_1, UTF_8}
import java.security.MessageDigest
import java.util.{Base64, UUID}
import java.util.concurrent.{ConcurrentHashMap, Executors}
import scala.util.control.NonFatal

/** Play Live for the local server: a WebSocket endpoint on its own port, standing in for the deployed WebSocket API.
  *
  * Its own port because the JDK's `HttpServer`, which serves the rest of the engine locally, cannot hand a connection
  * over to another protocol. And written here rather than taken from a library because what it needs of RFC 6455 is
  * small — the handshake, text frames out, a pong for a ping, a close — and the engines keep their dependencies to what
  * a Lambda cold start can afford.
  *
  * A connection's opening and closing are delivered to the routes as the deployed API delivers them — a `CONNECT` and a
  * `DISCONNECT` request on `/live`, with the connection's id — so that whether a connection is admitted is decided by
  * the same code in both places. Anything the page sends is read and ignored: its only message is a keep-alive.
  *
  * Bound on construction, to the loopback address only, so that [[url]] is known before the routes it is handed to
  * exist; [[serve]] starts accepting once they do.
  */
class LocalLiveServer(port: Int) extends LiveChannel {

    private val server = ServerSocket(port, 50, InetAddress.getLoopbackAddress)
    private val connections = ConcurrentHashMap[String, Connection]()
    private val threads = Executors.newVirtualThreadPerTaskExecutor()

    def boundPort: Int = server.getLocalPort

    def url: String = s"ws://localhost:$boundPort/live"

    def serve(routes: EngineRequest => EngineResponse): Unit = {
        val acceptor = Thread(() =>
            while (!server.isClosed)
                try {
                    val socket = server.accept()
                    threads.submit((() => handle(socket, routes)): Runnable)
                } catch { case _: IOException => () }
        )
        acceptor.setDaemon(true)
        acceptor.setName("play-live-accept")
        acceptor.start()
    }

    def close(): Unit = {
        server.close()
        threads.shutdownNow()
    }

    /** A connection still opening is reported as present — its subscription is made before the handshake is answered,
      * and a push in between must not be taken for a connection that has gone — and the push is dropped, since the page
      * fetches its state the moment the connection opens.
      */
    def send(connectionId: String, message: String): Boolean =
        Option(connections.get(connectionId)) match {
            case None             => false
            case Some(connection) => connection.text(message)
        }

    private def handle(socket: Socket, routes: EngineRequest => EngineResponse): Unit = {
        val id = UUID.randomUUID().toString
        try {
            val in = DataInputStream(BufferedInputStream(socket.getInputStream))
            val out = socket.getOutputStream
            val (target, headers) = LocalLiveServer.readHandshake(in)
            headers.get("sec-websocket-key") match {
                case None => LocalLiveServer.refuse(out, 400, "a WebSocket handshake was expected")
                case Some(key) =>
                    val uri = URI.create(target)
                    val connection = Connection(out)
                    connections.put(id, connection)
                    val admitted = routes(
                      EngineRequest(
                        "CONNECT",
                        "/live",
                        LocalEngineServer.queryOf(Option(uri.getRawQuery)),
                        headers = headers,
                        connectionId = Some(id)
                      )
                    )
                    if (admitted.status / 100 != 2) {
                        connections.remove(id)
                        LocalLiveServer.refuse(out, admitted.status, admitted.body)
                    } else
                        try {
                            connection.accept(LocalLiveServer.acceptKey(key))
                            connection.read(in)
                        } finally {
                            connections.remove(id)
                            routes(EngineRequest("DISCONNECT", "/live", connectionId = Some(id)))
                        }
            }
        } catch {
            case _: IOException => ()
            case NonFatal(e)    => Log.failure(e, "a Play Live connection")
        } finally {
            connections.remove(id)
            socket.close()
        }
    }

    private class Connection(out: OutputStream) {

        @volatile private var open = false

        def accept(acceptKey: String): Unit = synchronized {
            out.write(
              ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                  s"Sec-WebSocket-Accept: $acceptKey\r\n\r\n").getBytes(ISO_8859_1)
            )
            out.flush()
            open = true
        }

        def text(message: String): Boolean =
            if (!open) true
            else
                try {
                    frame(0x1, message.getBytes(UTF_8))
                    true
                } catch { case _: IOException => false }

        /** Reads frames until the page closes the connection or it drops. */
        def read(in: DataInputStream): Unit = {
            var reading = true
            while (reading) {
                val first = in.read()
                if (first < 0) reading = false
                else {
                    val opcode = first & 0x0f
                    val second = in.readUnsignedByte()
                    val length = (second & 0x7f) match {
                        case 126 => in.readUnsignedShort().toLong
                        case 127 => in.readLong()
                        case n   => n.toLong
                    }
                    // A keep-alive is a few bytes; anything this size is not from the play page.
                    if (length > LocalLiveServer.maxFrame) throw IOException(s"a $length-byte frame")
                    val mask = if ((second & 0x80) != 0) in.readNBytes(4) else Array.emptyByteArray
                    val payload = in.readNBytes(length.toInt)
                    if (payload.length < length) throw EOFException()
                    if (mask.nonEmpty) payload.indices.foreach(i => payload(i) = (payload(i) ^ mask(i % 4)).toByte)

                    opcode match {
                        case 0x8 =>
                            frame(0x8, payload.take(2))
                            reading = false
                        case 0x9 => frame(0xa, payload)
                        case _   => ()
                    }
                }
            }
        }

        private def frame(opcode: Int, payload: Array[Byte]): Unit = synchronized {
            out.write(0x80 | opcode)
            val n = payload.length
            if (n < 126) out.write(n)
            else if (n < 65536) {
                out.write(126)
                out.write(n >> 8)
                out.write(n & 0xff)
            } else {
                out.write(127)
                (7 to 0 by -1).foreach(i => out.write(((n.toLong >> (8 * i)) & 0xff).toInt))
            }
            out.write(payload)
            out.flush()
        }
    }
}

object LocalLiveServer {

    private val maxFrame = 64 * 1024

    /** RFC 6455's fixed GUID, which the accept key is the hash of together with the browser's key. */
    private val handshakeGuid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    def acceptKey(key: String): String =
        Base64.getEncoder.encodeToString(
          MessageDigest.getInstance("SHA-1").digest((key.trim + handshakeGuid).getBytes(ISO_8859_1))
        )

    /** The request line's target and the headers, lowercased as every other request's are. */
    private def readHandshake(in: InputStream): (String, Map[String, String]) = {
        val bytes = java.io.ByteArrayOutputStream()
        var tail = 0
        while (tail != 0x0d0a0d0a) {
            val b = in.read()
            if (b < 0) throw EOFException()
            if (bytes.size > 16 * 1024) throw IOException("an oversized handshake")
            bytes.write(b)
            tail = (tail << 8) | b
        }
        val lines = String(bytes.toByteArray, ISO_8859_1).split("\r\n").toList
        val target = lines.headOption.map(_.split(" ")).collect { case Array(_, t, _*) => t }.getOrElse("/")
        val headers = lines
            .drop(1)
            .flatMap { line =>
                line.split(":", 2) match {
                    case Array(name, value) => Some(name.trim.toLowerCase -> value.trim)
                    case _                  => None
                }
            }
            .toMap
        (target, headers)
    }

    private def refuse(out: OutputStream, status: Int, body: String): Unit = {
        val bytes = body.getBytes(UTF_8)
        out.write(
          (s"HTTP/1.1 $status Refused\r\ncontent-type: application/json\r\ncontent-length: ${bytes.length}\r\n" +
              "connection: close\r\n\r\n").getBytes(ISO_8859_1)
        )
        out.write(bytes)
        out.flush()
    }
}
