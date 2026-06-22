package com.smsgateway.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*

class WebServer(private val context: Context, private val port: Int) {

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var serverThread: Thread? = null

    fun start() {
        isRunning = true
        serverThread = Thread {
            try {
                serverSocket = ServerSocket(port)
                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    Thread { handleClient(client) }.start()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.also { it.start() }
    }

    fun stop() {
        isRunning = false
        serverSocket?.close()
        serverThread?.interrupt()
    }

    private fun handleClient(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream())

            val requestLine = reader.readLine() ?: return
            val path = requestLine.split(" ").getOrNull(1) ?: "/"

            // Read all headers
            val headers = mutableMapOf<String, String>()
            var line = reader.readLine()
            while (!line.isNullOrEmpty()) {
                val parts = line.split(": ", limit = 2)
                if (parts.size == 2) headers[parts[0]] = parts[1]
                line = reader.readLine()
            }

            when {
                path == "/" || path == "/index.html" -> sendHtml(writer, buildDashboardHtml())
                path == "/api/inbox" -> sendJson(writer, getInboxJson())
                path == "/api/sent" -> sendJson(writer, getSentJson())
                path == "/api/targets" -> sendJson(writer, getTargetsJson())
                path.startsWith("/api/send") -> {
                    val body = readBody(reader, headers["Content-Length"]?.toIntOrNull() ?: 0)
                    handleSendRequest(writer, body)
                }
                else -> send404(writer)
            }

            writer.flush()
            socket.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun readBody(reader: BufferedReader, length: Int): String {
        if (length <= 0) return ""
        val buffer = CharArray(length)
        reader.read(buffer, 0, length)
        return String(buffer)
    }

    private fun handleSendRequest(writer: PrintWriter, body: String) {
        try {
            val json = JSONObject(body)
            val number = json.getString("number")
            val message = json.getString("message")
            val success = SmsSender.sendSms(number, message)
            val response = JSONObject().apply {
                put("success", success)
                put("message", if (success) "Message sent!" else "Failed to send")
            }
            sendJson(writer, response.toString())
        } catch (e: Exception) {
            sendJson(writer, """{"success":false,"message":"Invalid request"}""")
        }
    }

    private fun getInboxJson(): String {
        val arr = JSONArray()
        SmsReader.getInbox(context, 20).forEach { msg ->
            arr.put(JSONObject().apply {
                put("sender", msg.sender)
                put("body", msg.body)
                put("time", formatTime(msg.timestamp))
            })
        }
        return arr.toString()
    }

    private fun getSentJson(): String {
        val arr = JSONArray()
        SmsReader.getSent(context, 20).forEach { msg ->
            arr.put(JSONObject().apply {
                put("sender", msg.sender)
                put("body", msg.body)
                put("time", formatTime(msg.timestamp))
            })
        }
        return arr.toString()
    }

    private fun getTargetsJson(): String {
        val arr = JSONArray()
        ForwardManager.getForwardTargets(context).forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id)
                put("address", t.address)
                put("type", t.type.name)
            })
        }
        return arr.toString()
    }

    private fun formatTime(ts: Long): String =
        SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(ts))

    private fun sendHtml(writer: PrintWriter, html: String) {
        writer.print("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n")
        writer.print(html)
    }

    private fun sendJson(writer: PrintWriter, json: String) {
        writer.print("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n")
        writer.print(json)
    }

    private fun send404(writer: PrintWriter) {
        writer.print("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\nNot Found")
    }

    private fun buildDashboardHtml(): String {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>SMS Gateway Dashboard</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body { font-family: 'Segoe UI', sans-serif; background: #0f172a; color: #e2e8f0; min-height: 100vh; }
  header { background: #1e293b; padding: 16px 24px; display: flex; align-items: center; gap: 12px; border-bottom: 1px solid #334155; }
  header h1 { font-size: 20px; font-weight: 700; color: #38bdf8; }
  .status-dot { width: 10px; height: 10px; border-radius: 50%; background: #22c55e; animation: pulse 2s infinite; }
  @keyframes pulse { 0%,100%{opacity:1} 50%{opacity:.4} }
  .tabs { display: flex; gap: 4px; padding: 16px 24px 0; }
  .tab { padding: 10px 20px; border-radius: 8px 8px 0 0; cursor: pointer; font-size: 14px; font-weight: 600; background: #1e293b; color: #94a3b8; border: none; transition: all .2s; }
  .tab.active { background: #0ea5e9; color: #fff; }
  .panel { display: none; padding: 24px; }
  .panel.active { display: block; }
  .card { background: #1e293b; border-radius: 12px; padding: 16px; margin-bottom: 12px; border: 1px solid #334155; }
  .msg-sender { font-weight: 700; color: #38bdf8; font-size: 15px; }
  .msg-time { font-size: 12px; color: #64748b; margin: 4px 0; }
  .msg-body { color: #cbd5e1; font-size: 14px; line-height: 1.5; }
  .send-form { background: #1e293b; border-radius: 12px; padding: 20px; border: 1px solid #334155; }
  .send-form input, .send-form textarea { width: 100%; background: #0f172a; border: 1px solid #334155; border-radius: 8px; padding: 10px 14px; color: #e2e8f0; font-size: 14px; margin-bottom: 12px; outline: none; }
  .send-form textarea { height: 100px; resize: vertical; font-family: inherit; }
  .send-form input:focus, .send-form textarea:focus { border-color: #0ea5e9; }
  .btn { background: #0ea5e9; color: #fff; border: none; padding: 12px 24px; border-radius: 8px; cursor: pointer; font-weight: 700; font-size: 15px; width: 100%; transition: background .2s; }
  .btn:hover { background: #0284c7; }
  .btn:active { background: #0369a1; }
  .empty { text-align: center; color: #475569; padding: 40px; font-size: 15px; }
  .badge { display: inline-block; background: #0ea5e9; color: #fff; border-radius: 99px; padding: 2px 8px; font-size: 11px; font-weight: 700; margin-left: 6px; }
  .alert { padding: 12px 16px; border-radius: 8px; margin-bottom: 16px; font-size: 14px; font-weight: 600; display: none; }
  .alert.success { background: #14532d; color: #86efac; border: 1px solid #166534; }
  .alert.error { background: #7f1d1d; color: #fca5a5; border: 1px solid #991b1b; }
</style>
</head>
<body>
<header>
  <div class="status-dot"></div>
  <h1>📱 SMS Gateway</h1>
  <span style="margin-left:auto;font-size:13px;color:#64748b;">Local Dashboard</span>
</header>
<div class="tabs">
  <button class="tab active" onclick="showTab('inbox')">📥 Inbox <span class="badge" id="inboxCount">...</span></button>
  <button class="tab" onclick="showTab('sent')">📤 Sent</button>
  <button class="tab" onclick="showTab('send')">✉️ Send</button>
</div>

<div id="inbox" class="panel active">
  <div id="inboxList"><div class="empty">Loading...</div></div>
</div>

<div id="sent" class="panel">
  <div id="sentList"><div class="empty">Loading...</div></div>
</div>

<div id="send" class="panel">
  <div class="send-form">
    <div id="sendAlert" class="alert"></div>
    <label style="font-size:13px;color:#94a3b8;font-weight:600;display:block;margin-bottom:6px;">📞 Phone Number</label>
    <input type="tel" id="sendNumber" placeholder="+919876543210">
    <label style="font-size:13px;color:#94a3b8;font-weight:600;display:block;margin-bottom:6px;">💬 Message</label>
    <textarea id="sendBody" placeholder="Type your message here..."></textarea>
    <button class="btn" onclick="sendSms()">📤 Send Message</button>
  </div>
</div>

<script>
function showTab(name) {
  document.querySelectorAll('.tab').forEach((t,i) => t.classList.toggle('active', ['inbox','sent','send'][i]===name));
  document.querySelectorAll('.panel').forEach(p => p.classList.toggle('active', p.id===name));
  if (name==='inbox') loadInbox();
  if (name==='sent') loadSent();
}

function renderMessages(data, container) {
  if (!data.length) { container.innerHTML = '<div class="empty">📭 No messages found</div>'; return; }
  container.innerHTML = data.map(m => `
    <div class="card">
      <div class="msg-sender">${"$"}{m.sender}</div>
      <div class="msg-time">🕐 ${"$"}{m.time}</div>
      <div class="msg-body">${"$"}{m.body}</div>
    </div>`).join('');
}

async function loadInbox() {
  const res = await fetch('/api/inbox');
  const data = await res.json();
  document.getElementById('inboxCount').textContent = data.length;
  renderMessages(data, document.getElementById('inboxList'));
}

async function loadSent() {
  const res = await fetch('/api/sent');
  const data = await res.json();
  renderMessages(data, document.getElementById('sentList'));
}

async function sendSms() {
  const number = document.getElementById('sendNumber').value.trim();
  const message = document.getElementById('sendBody').value.trim();
  const alert = document.getElementById('sendAlert');
  if (!number || !message) { showAlert(alert, 'error', 'Please enter phone number and message'); return; }
  const res = await fetch('/api/send', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({number, message}) });
  const data = await res.json();
  if (data.success) {
    showAlert(alert, 'success', '✅ Message sent successfully!');
    document.getElementById('sendBody').value = '';
  } else {
    showAlert(alert, 'error', '❌ Failed to send. Check the number.');
  }
}

function showAlert(el, type, msg) {
  el.className = 'alert ' + type;
  el.textContent = msg;
  el.style.display = 'block';
  setTimeout(() => el.style.display = 'none', 4000);
}

// Auto-refresh inbox every 2 seconds
loadInbox();
setInterval(() => { if (document.getElementById('inbox').classList.contains('active')) loadInbox(); }, 2000);
</script>
</body>
</html>
        """.trimIndent()
    }
}
