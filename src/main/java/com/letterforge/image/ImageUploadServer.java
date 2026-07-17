package com.letterforge.image;

import com.letterforge.LetterForge;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Lightweight embedded HTTP server for direct image uploads.
 *
 * Security hardening vs. previous version:
 *  - bind-address configurable (default 0.0.0.0; set to 127.0.0.1 when behind Nginx)
 *  - Semaphore limits concurrent POST handlers → 503 under load instead of unbounded threads
 *  - Content-Length not trusted; body capped at maxBytes + 1 during read
 *  - Content filter (HSV skin-tone) applied before storing ready tiles
 *
 * Endpoints:
 *   GET  /upload?token=<tok>  → HTML upload form
 *   POST /upload?token=<tok>  → binary image body (Content-Type: application/octet-stream)
 */
public class ImageUploadServer {

    private static final String HTML = """
        <!DOCTYPE html>
        <html lang="pt-BR">
        <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>LetterForge — Enviar Imagem</title>
        <style>
          body{font-family:sans-serif;max-width:420px;margin:48px auto;padding:0 20px;color:#222}
          h2{color:#c8a44a}
          button{background:#c8a44a;color:#fff;border:0;padding:10px 24px;cursor:pointer;border-radius:4px;font-size:1em}
          button:hover{background:#a8842a}
          #s{margin-top:16px;padding:10px;border-radius:4px;display:none}
          .ok{background:#d4edda;color:#155724}
          .err{background:#f8d7da;color:#721c24}
        </style></head>
        <body>
        <h2>&#x2709; LetterForge</h2>
        <p>Selecione uma imagem (PNG, JPG) de até 5 MB e clique em <strong>Enviar</strong>.</p>
        <input type="file" id="f" accept="image/*"><br><br>
        <button onclick="upload()">Enviar</button>
        <div id="s"></div>
        <script>
        function upload(){
          var f=document.getElementById('f').files[0];
          if(!f){alert('Selecione uma imagem primeiro.');return;}
          var t=new URLSearchParams(window.location.search).get('token');
          var s=document.getElementById('s');
          s.className='';s.style.display='block';s.textContent='Enviando...';
          fetch('/upload?token='+t,{method:'POST',
            headers:{'Content-Type':'application/octet-stream'},body:f})
          .then(function(r){return r.json()})
          .then(function(d){
            if(d.status==='ok'){
              s.className='ok';
              s.textContent='\\u2713 Upload realizado! Volte ao jogo e use: /carta imagem confirmar '+t;
            }else{
              s.className='err';
              s.textContent='\\u2717 Erro: '+d.error;
            }
          }).catch(function(e){s.className='err';s.textContent='Erro de rede: '+e.message;});
        }
        </script></body></html>
        """;

    private final LetterForge plugin;
    private final int  port;
    private final int  maxBytes;
    private final long tokenTtlMs;

    // Limits concurrent POST handlers to prevent thread exhaustion
    private final Semaphore concurrency;

    private final Map<String, TokenEntry> tokens = new ConcurrentHashMap<>();
    private final Map<String, byte[][]>   ready  = new ConcurrentHashMap<>();

    private HttpServer server;
    private ExecutorService httpExecutor;

    public ImageUploadServer(LetterForge plugin) {
        this.plugin      = plugin;
        this.port        = plugin.getConfig().getInt("image-upload.port", 8517);
        this.maxBytes    = plugin.getConfig().getInt("image-upload.max-file-size-mb", 5) * 1024 * 1024;
        this.tokenTtlMs  = plugin.getConfig().getLong("image-upload.token-ttl-seconds", 300) * 1000L;
        int maxConcurrent = plugin.getConfig().getInt("image-upload.max-concurrent", 3);
        this.concurrency  = new Semaphore(maxConcurrent);
    }

    public void start() throws IOException {
        String bindAddr = plugin.getConfig().getString("image-upload.bind-address", "127.0.0.1");
        server = HttpServer.create(new InetSocketAddress(bindAddr, port), 0);
        server.createContext("/upload", this::handle);
        httpExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "letterforge-http");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(httpExecutor);
        server.start();
        plugin.getLogger().info("[LetterForge] Upload server ativo em " + bindAddr + ":" + port + ".");
    }

    public void stop() {
        if (server != null) server.stop(0);
        // server.stop() NÃO encerra o executor customizado — sem isto as 4 threads
        // "letterforge-http" vazam a cada reload/disable do plugin.
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
            httpExecutor = null;
        }
    }

    public String generateToken(UUID playerUuid, String serverIp) {
        tokens.entrySet().removeIf(e -> e.getValue().isExpired());
        ready.entrySet().removeIf(e -> {
            TokenEntry te = tokens.get(e.getKey());
            return te == null || te.isExpired();
        });
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        tokens.put(token, new TokenEntry(playerUuid, System.currentTimeMillis() + tokenTtlMs));
        return "http://" + serverIp + ":" + port + "/upload?token=" + token;
    }

    public Optional<byte[][]> consumeReady(String token, UUID callerUuid) {
        TokenEntry entry = tokens.get(token);
        if (entry == null || entry.isExpired()) {
            tokens.remove(token);
            ready.remove(token);
            return Optional.empty();
        }
        if (!entry.playerUuid().equals(callerUuid)) {
            return Optional.empty();
        }
        byte[][] tiles = ready.remove(token);
        tokens.remove(token);
        return Optional.ofNullable(tiles);
    }

    // -----------------------------------------------------------------------
    // HTTP handler
    // -----------------------------------------------------------------------

    private void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String token  = parseToken(ex.getRequestURI().getQuery());

        if ("GET".equalsIgnoreCase(method)) {
            sendHtml(ex, 200, HTML);
            return;
        }
        if (!"POST".equalsIgnoreCase(method)) {
            sendJson(ex, 405, "{\"error\":\"method not allowed\"}");
            return;
        }
        if (token == null || !tokens.containsKey(token) || tokens.get(token).isExpired()) {
            sendJson(ex, 403, "{\"error\":\"token invalido ou expirado\"}");
            return;
        }

        // Concurrency gate — reject with 503 when all slots are busy
        if (!concurrency.tryAcquire()) {
            sendJson(ex, 503, "{\"error\":\"servidor sobrecarregado, tente novamente em instantes\"}");
            return;
        }

        byte[] body;
        try {
            body = ex.getRequestBody().readNBytes(maxBytes + 1);
        } catch (IOException e) {
            concurrency.release();
            sendJson(ex, 400, "{\"error\":\"falha ao ler dados\"}");
            return;
        }
        if (body.length > maxBytes) {
            concurrency.release();
            sendJson(ex, 413, "{\"error\":\"arquivo maior que " + (maxBytes / 1024 / 1024) + " MB\"}");
            return;
        }

        final String tok        = token;
        final byte[] uploadData = body;
        final UUID   playerUuid = tokens.get(token).playerUuid();

        Thread.ofVirtual().start(() -> {
            try {
                // Decodifica uma única vez (com proteção contra PNG bomb) e reusa a imagem
                // para os filtros (HSV/NSFW) e para a geração dos tiles.
                BufferedImage img = ImageProcessor.decode(uploadData);

                // Content filter (HSV) — check before processing
                if (plugin.getConfig().getBoolean("content-filter.enabled", false)) {
                    float threshold = (float) plugin.getConfig().getDouble("content-filter.skin-threshold", 0.35);
                    float fraction  = ContentFilter.skinToneFraction(img);
                    if (fraction > threshold) {
                        plugin.getLogger().warning(String.format(
                            "[LetterForge] [ContentFilter] Upload bloqueado — token=%s player=%s skin=%.2f",
                            tok, playerUuid, fraction));
                        plugin.auditLog("CONTENT_FLAGGED", playerUuid.toString(), null, tok,
                                String.format("upload skin=%.2f > threshold=%.2f", fraction, threshold));
                        sendJson(ex, 200, "{\"status\":\"ok\",\"warning\":\"conteudo_em_revisao\"}");
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            var p = plugin.getServer().getPlayer(playerUuid);
                            if (p != null)
                                p.sendMessage(plugin.getMessages().prefixed("filter.upload-flagged"));
                        });
                        return;
                    }
                }

                // Filtro NSFW (modelo) — só roda se habilitado E se um addon registrou um scorer.
                if (plugin.getConfig().getBoolean("nsfw-filter.enabled", false)
                        && NsfwFilter.isAvailable()) {
                    try {
                        float nsfwThreshold = (float) plugin.getConfig().getDouble("nsfw-filter.threshold", 0.75);
                        float nsfwScore = NsfwFilter.score(img);
                        if (nsfwScore > nsfwThreshold) {
                            if (plugin.getConfig().getBoolean("nsfw-filter.audit-log", true)) {
                                plugin.auditLog("NSFW_FLAGGED", playerUuid.toString(), null, tok,
                                        String.format("upload nsfw=%.2f > threshold=%.2f", nsfwScore, nsfwThreshold));
                            }
                            if (plugin.getConfig().getBoolean("nsfw-filter.notify-admin", true)) {
                                plugin.getLogger().warning(String.format(
                                        "[LetterForge] [NSFW] Upload bloqueado — token=%s player=%s score=%.2f",
                                        tok, playerUuid, nsfwScore));
                            }
                            sendJson(ex, 200, "{\"status\":\"ok\",\"warning\":\"conteudo_em_revisao\"}");
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                var p = plugin.getServer().getPlayer(playerUuid);
                                if (p != null)
                                    p.sendMessage(plugin.getMessages().prefixed("filter.upload-flagged"));
                            });
                            return;
                        }
                    } catch (Exception nsfwErr) {
                        // Falha de inferência não deve impedir o upload — apenas registra.
                        plugin.getLogger().warning("[LetterForge] [NSFW] Erro na inferência: " + nsfwErr.getMessage());
                    }
                }

                byte[][] tiles = ImageProcessor.fromImage(img, 1, 1);
                ready.put(tok, tiles);
                sendJson(ex, 200, "{\"status\":\"ok\"}");
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    var p = plugin.getServer().getPlayer(playerUuid);
                    if (p != null)
                        p.sendMessage(plugin.getMessages().prefixed("upload.received",
                                java.util.Map.of("token", tok)));
                });
            } catch (Exception e) {
                sendJson(ex, 415, "{\"error\":\"formato invalido: " + sanitize(e.getMessage()) + "\"}");
            } finally {
                concurrency.release();
            }
        });
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void sendJson(HttpExchange ex, int code, String json) {
        try {
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            ex.sendResponseHeaders(code, b.length);
            ex.getResponseBody().write(b);
            ex.getResponseBody().close();
        } catch (IOException ignored) {}
    }

    private void sendHtml(HttpExchange ex, int code, String html) {
        try {
            byte[] b = html.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            ex.sendResponseHeaders(code, b.length);
            ex.getResponseBody().write(b);
            ex.getResponseBody().close();
        } catch (IOException ignored) {}
    }

    private String parseToken(String query) {
        if (query == null) return null;
        for (String part : query.split("&")) {
            if (part.startsWith("token=")) return part.substring(6);
        }
        return null;
    }

    private String sanitize(String msg) {
        return msg == null ? "unknown" : msg.replace("\"", "'").replace("\n", " ");
    }

    private record TokenEntry(UUID playerUuid, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }
}
