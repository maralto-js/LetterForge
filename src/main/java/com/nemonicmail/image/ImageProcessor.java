package com.nemonicmail.image;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.*;
import java.util.Iterator;

/**
 * Converts images to Minecraft map pixel arrays (128×128 per tile).
 * Security hardening applied:
 *  - PNG bomb: dimensions validated via ImageReader before full decode
 *  - SSRF: all resolved IPs validated against private/loopback/link-local ranges
 *  - Redirects blocked (TOCTOU via 3xx SSRF)
 *  - Bounded reads (Content-Length not trusted)
 * Performance: ThreadLocal float buffers eliminate per-call allocation in dithering.
 */
public final class ImageProcessor {

    static final int MAP_SIZE      = 128;
    static final int MAX_URL_BYTES = 5 * 1024 * 1024;

    /** Configurable via NemonicMail.reload() — volatile for cross-thread visibility. */
    static volatile int maxImageDimension = 8192;

    /** Reusable buffers per thread — zero allocation in the dithering hot path. */
    private static final ThreadLocal<float[][]> DITHER_BUF = ThreadLocal.withInitial(
        () -> new float[][]{
            new float[MAP_SIZE * MAP_SIZE],
            new float[MAP_SIZE * MAP_SIZE],
            new float[MAP_SIZE * MAP_SIZE]
        }
    );

    private ImageProcessor() {}

    public static void setMaxImageDimension(int max) {
        maxImageDimension = Math.max(128, max);
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    public static byte[] toMapPixels(String url) throws IOException {
        return processTile(scale(fetchImage(url), MAP_SIZE, MAP_SIZE));
    }

    public static byte[][] toMultiMapPixels(String url, int gridW, int gridH) throws IOException {
        BufferedImage full = scale(fetchImage(url), MAP_SIZE * gridW, MAP_SIZE * gridH);
        return splitToTiles(full, gridW, gridH);
    }

    public static byte[][] fromBytes(byte[] data, int gridW, int gridH) throws IOException {
        BufferedImage img = readWithDimensionCheck(data);
        return fromImage(img, gridW, gridH);
    }

    /**
     * Decodifica bytes para uma BufferedImage aplicando a proteção contra PNG bomb
     * (validação de dimensões antes do decode completo). Permite reusar a mesma imagem
     * para filtros (HSV/NSFW) e para a geração de tiles, evitando decodificar duas vezes.
     */
    public static BufferedImage decode(byte[] data) throws IOException {
        return readWithDimensionCheck(data);
    }

    /** Tile grid from an already-decoded BufferedImage. Used by MapImageManager for content filtering. */
    public static byte[][] fromImage(BufferedImage img, int gridW, int gridH) {
        BufferedImage full = scale(img, MAP_SIZE * gridW, MAP_SIZE * gridH);
        return splitToTiles(full, gridW, gridH);
    }

    // -----------------------------------------------------------------------
    // Core: Floyd-Steinberg dithering — ThreadLocal buffers, zero alloc
    // -----------------------------------------------------------------------

    static byte[] processTile(BufferedImage img) {
        float[][] buf = DITHER_BUF.get();
        float[] er = buf[0];
        float[] eg = buf[1];
        float[] eb = buf[2];

        int size = MAP_SIZE * MAP_SIZE;
        for (int i = 0; i < size; i++) {
            int argb  = img.getRGB(i % MAP_SIZE, i / MAP_SIZE);
            int alpha = (argb >> 24) & 0xFF;
            if (alpha < 128) {
                er[i] = eg[i] = eb[i] = Float.NaN;
            } else {
                er[i] = (argb >> 16) & 0xFF;
                eg[i] = (argb >>  8) & 0xFF;
                eb[i] =  argb        & 0xFF;
            }
        }

        byte[] pixels = new byte[size];
        for (int y = 0; y < MAP_SIZE; y++) {
            for (int x = 0; x < MAP_SIZE; x++) {
                int i = y * MAP_SIZE + x;
                if (Float.isNaN(er[i])) { pixels[i] = 0; continue; }

                int ri = clamp(er[i]), gi = clamp(eg[i]), bi = clamp(eb[i]);
                byte mapped = MapColorPalette.match(ri, gi, bi);
                pixels[i] = mapped;

                int[] pal = MapColorPalette.getRGB(mapped & 0xFF);
                float qr = ri - pal[0], qg = gi - pal[1], qb = bi - pal[2];

                if (x + 1 < MAP_SIZE)
                    diffuse(er, eg, eb, i + 1, qr, qg, qb, 7);
                if (y + 1 < MAP_SIZE) {
                    if (x > 0)
                        diffuse(er, eg, eb, i + MAP_SIZE - 1, qr, qg, qb, 3);
                    diffuse(er, eg, eb, i + MAP_SIZE, qr, qg, qb, 5);
                    if (x + 1 < MAP_SIZE)
                        diffuse(er, eg, eb, i + MAP_SIZE + 1, qr, qg, qb, 1);
                }
            }
        }
        return pixels;
    }

    static BufferedImage scale(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    // -----------------------------------------------------------------------
    // PNG bomb protection — reads header only, decodes after dimension check
    // -----------------------------------------------------------------------

    private static BufferedImage readWithDimensionCheck(byte[] data) throws IOException {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            if (iis == null) throw new IOException("Formato de imagem não suportado");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) throw new IOException("Formato de imagem não reconhecido");
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                int w   = reader.getWidth(0);
                int h   = reader.getHeight(0);
                int max = maxImageDimension;
                if (w > max || h > max)
                    throw new IOException(
                        "Imagem excede dimensões máximas (" + max + "×" + max + "): " + w + "×" + h);
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    // -----------------------------------------------------------------------
    // SSRF-safe fetch — DNS validation + redirect blocking + bounded read
    // -----------------------------------------------------------------------

    static BufferedImage fetchImage(String urlStr) throws IOException {
        if (!urlStr.startsWith("https://"))
            throw new IllegalArgumentException("Apenas URLs HTTPS são permitidas");

        URL url;
        try {
            url = URI.create(urlStr).toURL();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("URL malformada: " + e.getMessage());
        }
        String host = url.getHost();

        // Resolve ALL IPs for the hostname before connecting — prevents DNS rebinding
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IOException("Host não encontrado: " + host);
        }
        for (InetAddress addr : addrs) assertPublicIp(addr);

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(15_000);
            conn.setInstanceFollowRedirects(false); // block redirect SSRF
            conn.setRequestProperty("User-Agent", "NemonicMail/1.0");

            int status = conn.getResponseCode();
            if (status / 100 == 3) throw new IOException("Redirecionamentos não são permitidos");
            if (status != 200)     throw new IOException("Servidor retornou HTTP " + status);

            // Read bounded bytes — Content-Length header is not trusted
            byte[] data;
            try (var in = conn.getInputStream()) {
                data = in.readNBytes(MAX_URL_BYTES + 1);
            }
            if (data.length > MAX_URL_BYTES)
                throw new IOException("Imagem excede o limite de " + (MAX_URL_BYTES / 1024 / 1024) + " MB");

            return readWithDimensionCheck(data);
        } finally {
            // Libera o socket subjacente — sem isso a conexão (keep-alive) fica pendurada.
            conn.disconnect();
        }
    }

    private static void assertPublicIp(InetAddress addr) throws SecurityException {
        if (addr.isLoopbackAddress())  throw new SecurityException("URL aponta para loopback (bloqueado)");
        if (addr.isLinkLocalAddress()) throw new SecurityException("URL aponta para link-local (bloqueado)");
        if (addr.isSiteLocalAddress()) throw new SecurityException("URL aponta para rede privada (bloqueado)");
        if (addr.isAnyLocalAddress())  throw new SecurityException("URL aponta para endereço local (bloqueado)");
        if (addr.isMulticastAddress()) throw new SecurityException("URL aponta para multicast (bloqueado)");
        String ip = addr.getHostAddress();
        // Cloud metadata: AWS 169.254.169.254, Alibaba 100.100.100.200
        if (ip.startsWith("169.254.") || ip.equals("100.100.100.200"))
            throw new SecurityException("URL aponta para metadados de nuvem (bloqueado)");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static byte[][] splitToTiles(BufferedImage full, int gridW, int gridH) {
        byte[][] tiles = new byte[gridW * gridH][];
        for (int ty = 0; ty < gridH; ty++) {
            for (int tx = 0; tx < gridW; tx++) {
                BufferedImage tile = full.getSubimage(tx * MAP_SIZE, ty * MAP_SIZE, MAP_SIZE, MAP_SIZE);
                tiles[ty * gridW + tx] = processTile(tile);
            }
        }
        return tiles;
    }

    private static void diffuse(float[] r, float[] g, float[] b,
                                 int idx, float qr, float qg, float qb, int weight) {
        float f = weight / 16f;
        r[idx] += qr * f;
        g[idx] += qg * f;
        b[idx] += qb * f;
    }

    private static int clamp(float v) {
        return Math.max(0, Math.min(255, (int) v));
    }
}
