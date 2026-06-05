package com.nemonicmail.image;

import java.awt.image.BufferedImage;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Fachada/registro do filtro NSFW. O core é LEVE: não embute ONNX Runtime nem nenhum
 * modelo de ML. A pontuação real é fornecida por um {@link NsfwScorer} registrado por um
 * addon opcional (ex.: NemonicMail-NSFW-Model premium), que carrega o ONNX no próprio
 * classloader e chama {@link #register}.
 *
 * <p>Se nenhum addon registrar um scorer, {@link #isAvailable()} retorna {@code false} e a
 * moderação fica a cargo apenas do {@link ContentFilter} (HSV), sem qualquer dependência pesada.</p>
 */
public final class NsfwFilter {

    private static volatile NsfwScorer scorer;
    private static Logger logger;

    private NsfwFilter() {}

    /** Chamado pelo core no onEnable. Apenas guarda o logger — não carrega nenhum modelo. */
    public static void init(Logger pluginLogger) {
        logger = pluginLogger;
    }

    /** Registra o motor de pontuação (chamado pelo addon premium). */
    public static void register(NsfwScorer s) {
        scorer = s;
        if (logger != null && s != null) {
            logger.info("[NsfwFilter] Motor NSFW registrado: " + s.engineName());
        }
    }

    /**
     * Adaptador para registro reflexivo sem acoplamento de compilação ao core:
     * o addon passa um {@link Function} (tipo do JDK, visível a todos os classloaders)
     * e o nome do motor. Usado por NsfwModelAddon via reflexão.
     */
    public static void registerFunction(Function<BufferedImage, Float> fn, String name) {
        register(new NsfwScorer() {
            @Override public float score(BufferedImage image) { return fn.apply(image); }
            @Override public String engineName() { return name; }
        });
    }

    public static boolean isAvailable() {
        return scorer != null;
    }

    /**
     * Pontua a imagem delegando ao scorer registrado.
     * @throws IllegalStateException se nenhum scorer estiver registrado (verifique {@link #isAvailable()} antes).
     */
    public static float score(BufferedImage image) throws Exception {
        NsfwScorer s = scorer;
        if (s == null) throw new IllegalStateException("Nenhum NsfwScorer registrado (addon ausente)");
        return s.score(image);
    }

    /** Libera o motor registrado, se houver. Idempotente. */
    public static void close() {
        NsfwScorer s = scorer;
        scorer = null;
        if (s != null) {
            try {
                s.close();
            } catch (Exception e) {
                if (logger != null) logger.warning("[NsfwFilter] Erro ao fechar motor NSFW: " + e.getMessage());
            }
        }
    }
}
